#!/usr/bin/env python3
"""Read-only PR state for the android-pr skill.

Works out the facts the skill reports, so the model writes prose, not counts:
branch and staging state, which files belong in the PR, which must never be
staged, secret-file risks, which CI workflows the PR triggers, which local
verification and audit evidence is current for the code now, and the gates.

It only runs read-only git commands, with the options in
.claude/tools/safe_paths.py, and only on the repository that contains it. The
only file it writes is --files-out: a new, NUL-separated list of the paths to
stage, in a temporary directory, written only when the scope, staging, file
name and secret gates pass. Stage it with
  git --literal-pathspecs add --pathspec-from-file=FILE --pathspec-file-nul

Run with `python3 -I`. Compatible with Python 3.9.

Commands:
  state         --repo ROOT [--base main] [--include PATH]... [--evidence-dir DIR]...
                [--files-out PATH]
  check-staged  --repo ROOT --expect FILE [--include PATH]...
                The staged files must equal both the list in FILE and the PR files
                recomputed from --include, and none may be never-stage or secret-risk.
"""

import argparse
import fnmatch
import importlib.util
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FINGERPRINT = os.path.join(os.path.dirname(HERE), "android-verify", "fingerprint.py")
_spec = importlib.util.spec_from_file_location(
    "safe_paths", os.path.join(os.path.dirname(os.path.dirname(HERE)), "tools", "safe_paths.py"))
safe_paths = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(safe_paths)

# Local-only files. Never staged, even when an --include covers them.
NEVER_STAGE_PREFIXES = (".agents/", ".argent/", "goldie/", "docs/android-audit/reports/", ".idea/")
NEVER_STAGE_FILES = ("skills-lock.json", ".claude/settings.local.json")
NEVER_STAGE_NAMES = (".DS_Store",)
NEVER_STAGE_SUFFIXES = (".pyc",)
# Staged only when included by exact path.
NEEDS_APPROVAL_FILES = (".gitignore",)

# Matched case-insensitively.
SECRET_NAMES = ("google-services.json", "googleservice-info.plist", "keystore.properties", "local.properties")
SECRET_GLOBS = ("*.jks", "*.keystore", "*.p12", "*.pem", ".env", ".env.*", "*service-account*.json")
# Built from parts so this file does not match its own patterns.
SECRET_MARKERS = (
    ("private key block", re.compile("-----BEGIN " + r"(?:[A-Z]+ )?" + "PRIVATE KEY-----")),
    ("service account JSON", re.compile(r'"type"\s*:\s*"service' + '_account"')),
    ("private_key field", re.compile(r'"private' + r'_key"\s*:')),
    ("GitHub token", re.compile(r"\bgh" + r"[pousr]_[A-Za-z0-9]{36}\b|\bgithub" + r"_pat_[A-Za-z0-9_]{22,}")),
    ("Google API key", re.compile(r"\bAI" + r"za[0-9A-Za-z_\-]{35}")),
    ("oauth_token field", re.compile(r"\boauth" + r"_token\s*[:=]\s*\S")),
)
SCAN_BYTES = 1024 * 1024  # the first and the last MiB of each file

ANDROID_CI_PATHS = ("android/", ".github/workflows/android-ci.yml")
CONTENT_CI_PATHS = ("scripts/content/", ".github/workflows/content-pipeline-ci.yml")
# Files android-audit reviews (its routing.md "reviewable files").
AUDIT_PATHS = ("android/", "firestore.rules", "firebase.json", ".github/workflows/", "docs/adr/",
               "website/privacy/", "scripts/content/firestore_upload")

PASSED = "Passed"
BLOCKED = "Blocked"
UNVERIFIED = "Unverified"
NOT_REQUIRED = "Not required"


def load_fingerprint():
    spec = importlib.util.spec_from_file_location("fingerprint", FINGERPRINT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def git(repo, *args, check=True):
    proc = safe_paths.run_git(repo, *args)
    if check and proc.returncode != 0:
        sys.exit("git %s failed: %s" % (" ".join(args), proc.stderr.decode().strip()))
    return proc.stdout.decode() if proc.returncode == 0 else None


def under(path, prefixes):
    return any(path == p or path.startswith(p) for p in prefixes)


def is_never_stage(path):
    return (under(path, NEVER_STAGE_PREFIXES) or path in NEVER_STAGE_FILES
            or os.path.basename(path) in NEVER_STAGE_NAMES or path.endswith(NEVER_STAGE_SUFFIXES)
            or "/__pycache__/" in "/" + path)


def invalid_name(path):
    """Control characters or a leading quote: such a path could split into
    several entries in a list, or be read as a quoted name."""
    return path.startswith('"') or any(ord(c) < 32 or ord(c) == 127 for c in path)


def shown(path):
    return repr(path) if invalid_name(path) else path


def secret_name(path):
    name = os.path.basename(path).lower()
    return name in SECRET_NAMES or any(fnmatch.fnmatchcase(name, g) for g in SECRET_GLOBS)


def secret_markers(repo, path):
    full = os.path.join(repo, path)
    if not os.path.isfile(full) or os.path.islink(full):
        return []
    with open(full, "rb") as handle:
        data = handle.read(SCAN_BYTES)
        size = os.path.getsize(full)
        if size > SCAN_BYTES:
            handle.seek(max(SCAN_BYTES, size - SCAN_BYTES))
            data += b"\n" + handle.read(SCAN_BYTES)
    text = data.decode("utf-8", errors="ignore")
    return [label for label, pattern in SECRET_MARKERS if pattern.search(text)]


def porcelain(repo):
    """Return [(status, path)] for every changed or untracked file."""
    raw = git(repo, "status", "--porcelain=v1", "-z", "--untracked-files=all")
    entries = raw.split("\0")
    result = []
    i = 0
    while i < len(entries):
        entry = entries[i]
        i += 1
        if not entry:
            continue
        status, path = entry[:2], entry[3:]
        result.append((status, path))
        if "R" in status or "C" in status:
            result.append((status, entries[i]))
            i += 1
    return result


def included(path, includes):
    for item in includes:
        item = item.rstrip("/")
        if path == item or path.startswith(item + "/"):
            return True
    return False


# ---------------------------------------------------------------- evidence

def read_summary(path):
    header, checks = {}, {}
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if line.startswith("check "):
                name, _, value = line[len("check "):].partition(": ")
                checks[name] = value
            else:
                key, sep, value = line.partition(": ")
                if sep and key in ("source", "run", "tier", "options", "head", "android_tree", "fingerprint"):
                    header[key] = value
    return header, checks


def find_summaries(dirs):
    found = []
    for directory in dirs:
        if not os.path.isdir(directory):
            continue
        for current, _, files in os.walk(directory):
            for name in sorted(files):
                if name.endswith("-summary.txt"):
                    found.append(os.path.join(current, name))
    return sorted(set(found))


def classify_evidence(repo, paths, fp, now):
    rows = []
    for path in paths:
        header, checks = read_summary(path)
        if "head" not in header or "android_tree" not in header:
            rows.append({"path": path, "state": "legacy", "reason": "no fingerprint", "header": header,
                         "checks": checks})
            continue
        current, reason = fp.is_current(repo, header["head"], header["android_tree"], now)
        rows.append({"path": path, "state": "current" if current else "stale", "reason": reason,
                     "header": header, "checks": checks})
    return rows


def ci_result(row):
    """Return (passed?, freshness label) for a local ci summary."""
    checks = row["checks"]
    needed = ("build", "unit tests", "lint")
    if not all(checks.get(name, "").startswith("Passed") for name in needed):
        return False, "not all of build, unit tests and lint passed"
    if checks.get("fingerprint", "Passed").startswith("Failed"):
        return False, "fingerprint changed during the run"
    if "--fresh" in row["header"].get("options", "") and checks.get("fresh", "").startswith("Passed"):
        return True, "fresh"
    if any("up to date" in checks.get(name, "") for name in needed):
        return True, "partly up to date"
    return True, "executed"


# ---------------------------------------------------------------- audit

def audit_reports(repo):
    directory = os.path.join(repo, "docs", "android-audit", "reports")
    if not os.path.isdir(directory):
        return []
    rows = []
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".md"):
            continue
        path = os.path.join(directory, name)
        with open(path, encoding="utf-8", errors="replace") as handle:
            text = handle.read()
        commit = re.search(r"^\| Branch / commit \| .*? @ ([0-9a-f]{7,40}) \|", text, re.M)
        scope = re.search(r"^\| Scope \| (.*?) \|$", text, re.M)
        section = re.search(r"^## 3\. Regressions.*?$(.*?)^## ", text, re.M | re.S)
        regressions = sorted(set(re.findall(r"AUD-\d+", section.group(1)))) if section else []
        rows.append({"path": path, "commit": commit.group(1) if commit else None,
                     "scope": scope.group(1) if scope else "unknown", "regressions": regressions})
    return rows


def audit_current(repo, commit, head):
    if not commit:
        return False, "no commit in the report header"
    full = git(repo, "rev-parse", "--verify", "--quiet", commit + "^{commit}", check=False)
    if not full:
        return False, "commit %s is not in this repository" % commit
    changed = git(repo, "diff", "--name-only", *safe_paths.DIFF_SAFE_OPTIONS, full.strip(), head,
                  "--", *AUDIT_PATHS).split()
    if changed:
        return False, "%d reviewable file(s) changed since %s" % (len(changed), commit)
    return True, "no reviewable changes since %s" % commit


# ---------------------------------------------------------------- state

def state(args):
    repo = args.repo
    fp = load_fingerprint()
    out = []

    branch = git(repo, "branch", "--show-current").strip()
    head = git(repo, "rev-parse", "HEAD").strip()
    upstream = git(repo, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}", check=False)
    upstream = upstream.strip() if upstream else None
    base_sha = git(repo, "merge-base", args.base, "HEAD").strip()
    out.append("branch: %s" % (branch or "(detached HEAD)"))
    out.append("head: %s" % head)
    if upstream:
        counts = git(repo, "rev-list", "--left-right", "--count", "%s...HEAD" % upstream).split()
        out.append("upstream: %s (behind %s, ahead %s)" % (upstream, counts[0], counts[1]))
    else:
        out.append("upstream: none")
    out.append("base: %s @ %s" % (args.base, base_sha[:12]))
    remote_base = git(repo, "rev-parse", "--verify", "--quiet", "origin/" + args.base, check=False)
    if remote_base:
        behind = git(repo, "rev-list", "--count", "HEAD..origin/" + args.base).strip()
        out.append("origin/%s: %s commit(s) not in this branch (local refs, not fetched)" % (args.base, behind))

    staged = staged_paths(repo)
    files = classify(repo, base_sha, args.include)
    pr_paths = [p for cls, _, p in files if cls in ("committed", "pr")]
    invalid = [p for cls, _, p in files if cls == "invalid-name"]
    out.append("staged: %s" % (", ".join(shown(p) for p in staged) if staged else "none"))
    for cls, status, path in files:
        out.append("file %s %s %s" % (cls, status, shown(path)))

    # Secrets: names anywhere in the PR or the working tree, markers in PR files.
    secrets = ["%s: secret-like file name" % shown(p) for c, _, p in files if c == "secret-risk"]
    secrets += ["%s: secret-like file name" % shown(p) for p in pr_paths if secret_name(p)]
    for path in pr_paths:
        secrets += ["%s: %s" % (path, label) for label in secret_markers(repo, path)]
    out.append("secrets: %s" % ("; ".join(secrets) if secrets else "none found"))

    android = [p for p in pr_paths if under(p, ANDROID_CI_PATHS)]
    content = [p for p in pr_paths if under(p, CONTENT_CI_PATHS)]
    reviewable = [p for p in pr_paths if under(p, AUDIT_PATHS)]
    instrumented = [p for p in android if p.startswith("android/app/src/androidTest/")
                    or p.startswith("android/app/src/main/res/") or ("/feature/" in p and "/ui/" in p)]
    out.append("android ci: %s" % ("triggered (%d files)" % len(android) if android
                                   else "not triggered (path filter: android/**, .github/workflows/android-ci.yml)"))
    out.append("content pipeline ci: %s" % ("triggered (%d files)" % len(content) if content else "not triggered"))
    out.append("instrumented relevant: %s" % ("yes (%d files)" % len(instrumented) if instrumented else "no"))

    now = fp.compute(repo)
    out.append("fingerprint now: %s" % now["fingerprint"])
    evidence_dirs = [os.path.join(repo, "docs", "android-audit", "reports", "verify"),
                     os.path.join(repo, "docs", "android-audit", "evidence")] + args.evidence_dir
    evidence = classify_evidence(repo, find_summaries(evidence_dirs), fp, now)
    for row in evidence:
        out.append("evidence %s %s %s (%s)" % (row["state"], row["header"].get("tier", "?"),
                                              rel(row["path"], repo), row["reason"]))
    audits = audit_reports(repo)
    dirty_reviewable = [p for c, _, p in files if c != "committed" and under(p, AUDIT_PATHS)]
    for row in audits:
        current, reason = audit_current(repo, row["commit"], head)
        if current and dirty_reviewable:
            current, reason = False, "%d uncommitted reviewable file(s)" % len(dirty_reviewable)
        row["current"] = current
        out.append("audit %s %s scope=%s regressions=%s (%s)" % (
            "current" if current else "stale", rel(row["path"], repo), row["scope"],
            ",".join(row["regressions"]) or "none", reason))

    gates = []
    if not pr_paths:
        gates.append(("G0 scope", BLOCKED, "no committed or included files"))
    else:
        gates.append(("G0 scope", PASSED, "%d file(s)" % len(pr_paths)))
    if not branch:
        gates.append(("G1 branch", BLOCKED, "detached HEAD"))
    elif branch == args.base:
        gates.append(("G1 branch", BLOCKED, "on %s; create a branch first" % args.base))
    else:
        gates.append(("G1 branch", PASSED, branch))
    if staged:
        gates.append(("G2 staging", BLOCKED, "%d file(s) already staged" % len(staged)))
    else:
        gates.append(("G2 staging", PASSED, "nothing staged"))
    if invalid:
        gates.append(("G2b file names", BLOCKED, "%d path(s) with control characters or a leading quote"
                      % len(invalid)))
    else:
        gates.append(("G2b file names", PASSED, "all paths printable"))
    gates.append(("G3 secrets", BLOCKED if secrets else PASSED, "%d finding(s)" % len(secrets) if secrets
                  else "none found"))

    if not android:
        gates.append(("G4 local verification", NOT_REQUIRED, "no files in Android CI scope"))
    else:
        passing = [(row, ci_result(row)) for row in evidence
                   if row["state"] == "current" and row["header"].get("tier") == "ci"]
        good = [(row, label) for row, (ok, label) in passing if ok]
        if good:
            row, label = sorted(good, key=lambda item: item[1] != "fresh")[0]
            gates.append(("G4 local verification", PASSED, "%s (%s)" % (rel(row["path"], repo), label)))
        else:
            gates.append(("G4 local verification", BLOCKED, "no current passing android-verify ci summary"))
        if instrumented:
            has = any(r["state"] == "current" and r["header"].get("tier") == "instrumented"
                      and r["checks"].get("instrumented tests", "").startswith("Passed") for r in evidence)
            gates.append(("G4b instrumented", PASSED if has else UNVERIFIED,
                          "current instrumented summary" if has else "no current instrumented run"))

    if not reviewable:
        gates.append(("G5 audit", NOT_REQUIRED, "no files android-audit reviews"))
    else:
        current = [row for row in audits if row.get("current")]
        if any(row["regressions"] for row in current):
            gates.append(("G5 audit", BLOCKED, "open regressions in a current audit report"))
        elif current:
            gates.append(("G5 audit", PASSED, rel(current[-1]["path"], repo)))
        else:
            gates.append(("G5 audit", UNVERIFIED, "no current audit report"))

    for name, result, detail in gates:
        out.append("gate %s: %s (%s)" % (name, result, detail))
    results = [result for _, result, _ in gates]
    if BLOCKED in results:
        readiness = "Blocked"
    elif UNVERIFIED in results:
        readiness = "Ready with unverified items"
    else:
        readiness = "Ready"
    out.append("readiness: %s" % readiness)

    if args.files_out:
        content_gates = ("G0 scope", "G2 staging", "G2b file names", "G3 secrets")
        blocking = [name for name, result, _ in gates if name in content_gates and result == BLOCKED]
        if blocking:
            out.append("files-out: not written (%s Blocked)" % ", ".join(blocking))
        else:
            stage = [p for c, _, p in files if c == "pr"]
            write_new_outside(args.files_out, "".join(p + "\0" for p in stage), repo)
            out.append("files-out: %s (%d paths to stage, NUL-separated)" % (args.files_out, len(stage)))
    print("\n".join(out))


def staged_paths(repo):
    return [p for p in git(repo, "diff", "--cached", "--name-only", "-z", *safe_paths.DIFF_SAFE_OPTIONS)
            .split("\0") if p]


def classify(repo, base_sha, includes):
    """Return [(class, status, path)]: committed files on the branch, then every
    changed or untracked file in the working tree."""
    committed = [p for p in git(repo, "diff", "--name-only", "-z", *safe_paths.DIFF_SAFE_OPTIONS,
                                base_sha, "HEAD").split("\0") if p]
    files = [("committed", "C", path) for path in committed]
    for status, path in porcelain(repo):
        if invalid_name(path):
            cls = "invalid-name"
        elif is_never_stage(path):
            cls = "never-stage"
        elif secret_name(path):
            cls = "secret-risk"
        elif path in NEEDS_APPROVAL_FILES and path not in includes:
            cls = "needs-approval"
        elif included(path, includes):
            cls = "pr"
        else:
            cls = "unrelated"
        files.append((cls, status.replace(" ", "."), path))
    return files


def rel(path, repo):
    return os.path.relpath(path, repo) if path.startswith(repo + os.sep) else path


def write_new_outside(path, text, repo):
    """The staging list goes to a new file in a temporary directory only."""
    try:
        safe_paths.write_new(path, text, repo, repo_allowed=())
    except ValueError as error:
        sys.exit(str(error))


def check_staged(args):
    repo = args.repo
    with open(args.expect, encoding="utf-8") as handle:
        expected = {p for p in handle.read().split("\0") if p}
    staged = set(staged_paths(repo))
    # Recomputed independently, so a tampered list cannot vouch for itself.
    base_sha = git(repo, "merge-base", args.base, "HEAD").strip()
    recomputed = {p for cls, _, p in classify(repo, base_sha, args.include) if cls == "pr"}
    extra, missing = sorted(staged - expected), sorted(expected - staged)
    not_pr = sorted(staged - recomputed)
    forbidden = sorted(p for p in staged if is_never_stage(p) or secret_name(p) or invalid_name(p))
    print("staged: %d, expected: %d, recomputed: %d" % (len(staged), len(expected), len(recomputed)))
    for path in extra:
        print("unexpected: %s" % shown(path))
    for path in missing:
        print("missing: %s" % shown(path))
    for path in not_pr:
        print("not a PR file: %s" % shown(path))
    for path in forbidden:
        print("forbidden: %s" % shown(path))
    ok = not extra and not missing and not not_pr and not forbidden and expected == recomputed
    print("staged check: %s" % ("Passed" if ok else "Failed"))
    sys.exit(0 if ok else 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    s = sub.add_parser("state")
    s.add_argument("--repo", required=True)
    s.add_argument("--base", default="main")
    s.add_argument("--include", action="append", default=[])
    s.add_argument("--evidence-dir", action="append", default=[])
    s.add_argument("--files-out")
    c = sub.add_parser("check-staged")
    c.add_argument("--repo", required=True)
    c.add_argument("--base", default="main")
    c.add_argument("--expect", required=True)
    c.add_argument("--include", action="append", default=[])
    args = parser.parse_args()
    try:
        args.repo = safe_paths.check_repo(args.repo, __file__)
        if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9._/-]*", args.base):
            raise ValueError("invalid --base %r" % args.base)
        for path in getattr(args, "evidence_dir", []) + [getattr(args, "expect", None)]:
            if path:
                safe_paths.check_readable(path, args.repo)
    except ValueError as error:
        sys.exit(str(error))
    {"state": state, "check-staged": check_staged}[args.command](args)


if __name__ == "__main__":
    main()
