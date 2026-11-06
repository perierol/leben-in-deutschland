#!/usr/bin/env python3
"""Deterministic report parsing for the android-verify skill.

Reads Gradle console logs, JUnit XML and Android lint XML, and prints the
counts and per-check results that the skill records as evidence. It never runs
Gradle. It only creates new files, never overwriting: in a temporary directory
(the session scratchpad), or in the repository only under
docs/android-audit/evidence/ (sanitize, --out) and
docs/android-audit/reports/verify/ (--out). See .claude/tools/safe_paths.py.

Run with `python3 -I` (isolated mode). Compatible with Python 3.9.

Commands:
  summarize  --repo ROOT [--console LOG] [--exit-code N]
             [--junit DIR]... [--junit-task TASK] [--junit-since EPOCH]
             [--lint XML] [--lint-task TASK] [--lint-since EPOCH]
             [--since EPOCH] [--changed FILE] [--expect-device SERIAL]
             [--target-test PATTERN] [--raw-out PATH] [--require-executed TASK]...
             [--meta RUN-META] [--out PATH]...
  compare    --baseline FILE --current FILE
  sanitize   --repo ROOT IN OUT

Freshness. With --console, each report's freshness follows its own Gradle
task (--junit-task, default :app:testDebugUnitTest; --lint-task, default
:app:lintReportDebug, which writes the report; lintDebug executes every run):
  executed or FAILED       -> only files written at or after its since count
  UP-TO-DATE or FROM-CACHE -> earlier files are reused, reported as
                              "Passed (up to date, not executed in this run)"
  SKIPPED, NO-SOURCE, absent -> "Not run"
--junit-since and --lint-since default to --since.

--require-executed (repeatable, for `ci --fresh`) adds a `check fresh` line:
Failed unless every listed task executed (or FAILED) in this log.

--meta reads run_gradle.sh's <run>-meta.txt: the summary starts with its source,
run, tier, options, head, android_tree, fingerprint, scope, commands and exit
codes, and gains a `check fingerprint` line (Failed when the fingerprint after
the run differs from the one before it). --exit-code and --since default to
the meta values. --out (repeatable) also writes the summary to a new file:
outside the repository, or under docs/android-audit/evidence/ or
docs/android-audit/reports/verify/; never overwriting.
"""

import argparse
import ast
import fnmatch
import importlib.util
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter

_spec = importlib.util.spec_from_file_location(
    "safe_paths", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tools", "safe_paths.py"))
safe_paths = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(safe_paths)

TASK_LINE = re.compile(r"^> Task (\S+)(?: (UP-TO-DATE|FROM-CACHE|SKIPPED|NO-SOURCE|FAILED))?\s*$")
# Only the tasks whose state the summary prints; every task is still recorded.
WATCHED_TASKS = ("test", "lint", "assemble", "connected")
REUSED = ("UP-TO-DATE", "FROM-CACHE")

PASSED = "Passed"
PASSED_UP_TO_DATE = "Passed (up to date, not executed in this run)"
FAILED = "Failed"
NOT_RUN = "Not run"
NEEDS_VERIFICATION = "Needs verification"


def rel(path, repo):
    path = os.path.realpath(path)
    repo = os.path.realpath(repo)
    if path == repo or path.startswith(repo + os.sep):
        return os.path.relpath(path, repo)
    return path


def repo_spellings(repo):
    """The repository path as reports may spell it: resolved, and on macOS also
    without the /private prefix that /var and /tmp resolve to."""
    real = os.path.realpath(repo)
    spellings = {real, os.path.abspath(repo)}
    if real.startswith("/private/"):
        spellings.add(real[len("/private"):])
    return sorted(spellings, key=len, reverse=True)


def scrub(text, repo):
    for spelling in repo_spellings(repo):
        text = text.replace(spelling, "<repo>")
    home = os.path.expanduser("~")
    if home and home != "/":
        text = text.replace(home, "~")
    return text


# ---------------------------------------------------------------- console

# A log can hold several invocations (`ci --fresh`). A task seen more than once
# keeps its strongest state, so a task executed in one step is not hidden by
# being UP-TO-DATE in the next.
STATE_RANK = {"FAILED": 4, "EXECUTED": 3, "UP-TO-DATE": 2, "FROM-CACHE": 2, "SKIPPED": 1, "NO-SOURCE": 1}


def parse_console(path):
    outcomes = []
    tasks = {}
    order = []
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.rstrip("\n")
            match = TASK_LINE.match(line)
            if match:
                name = match.group(1)
                state = match.group(2) or "EXECUTED"
                if name not in tasks:
                    order.append(name)
                    tasks[name] = state
                elif STATE_RANK[state] > STATE_RANK[tasks[name]]:
                    tasks[name] = state
            if line.startswith("BUILD SUCCESSFUL"):
                outcomes.append("BUILD SUCCESSFUL")
            elif line.startswith("BUILD FAILED"):
                outcomes.append("BUILD FAILED")
    if not outcomes:
        outcome = None
    elif "BUILD FAILED" in outcomes:
        outcome = "BUILD FAILED"
    else:
        outcome = "BUILD SUCCESSFUL"
    if len(outcomes) > 1:
        outcome += " (%d of %d invocations failed)" % (outcomes.count("BUILD FAILED"), len(outcomes))
    return outcome, tasks, order


def freshness(tasks, task, since):
    """Return (mode, since to apply, reason) for one report.

    mode is one of: fresh, reused, absent, unknown.
    """
    if tasks is None:
        if since:
            return "fresh", since, None
        return "unknown", 0.0, "no console log and no since time"
    state = tasks.get(task)
    if state is None:
        return "absent", since, "%s not in console" % task
    if state in REUSED:
        return "reused", 0.0, "%s %s" % (task, state)
    if state in ("SKIPPED", "NO-SOURCE"):
        return "absent", since, "%s %s" % (task, state)
    return "fresh", since, None


# ---------------------------------------------------------------- junit

def first_frame(text, class_name):
    simple = class_name.rsplit(".", 1)[-1]
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("at ") and simple in line:
            return line
    return None


def iter_suites(root):
    if root.tag == "testsuite":
        yield root
    for suite in root.iter("testsuite"):
        if suite is not root:
            yield suite


def parse_junit(dirs, since):
    suites = []
    stale = []
    devices = Counter()
    for directory in dirs:
        if not os.path.isdir(directory):
            continue
        for name in sorted(os.listdir(directory)):
            if not (name.startswith("TEST-") and name.endswith(".xml")):
                continue
            path = os.path.join(directory, name)
            if since and os.path.getmtime(path) < since:
                stale.append(path)
                continue
            root = ET.parse(path).getroot()
            for suite in iter_suites(root):
                device = None
                for prop in suite.iter("property"):
                    if prop.get("name") == "device":
                        device = prop.get("value")
                cases = []
                for case in suite.findall("testcase"):
                    status, detail = "PASS", None
                    failure = case.find("failure")
                    if failure is None:
                        failure = case.find("error")
                    if failure is not None:
                        status = "FAIL"
                        body = (failure.text or "") + "\n" + (failure.get("message") or "")
                        lines = [l for l in body.strip().splitlines() if l.strip()]
                        detail = [lines[0].strip()] if lines else []
                        frame = first_frame(body, case.get("classname") or suite.get("name"))
                        if frame:
                            detail.append(frame)
                    elif case.find("skipped") is not None:
                        status = "SKIP"
                    cases.append((status, case.get("name"), detail))
                if device:
                    devices[device] += len(cases)
                suites.append((suite.get("name"), device, cases))
    return suites, stale, devices


# ---------------------------------------------------------------- lint

def parse_lint(path, repo, changed):
    root = ET.parse(path).getroot()
    severities = Counter()
    ids = Counter()
    in_changed = []
    for issue in root.findall("issue"):
        severities[issue.get("severity")] += 1
        ids[issue.get("id")] += 1
        location = issue.find("location")
        if location is not None and changed:
            file_rel = rel(location.get("file", ""), repo)
            if file_rel in changed:
                in_changed.append("%s %s:%s" % (issue.get("id"), file_rel, location.get("line", "?")))
    return severities, ids, in_changed


def as_dict(counter):
    return dict(sorted(counter.items(), key=lambda item: (-item[1], item[0])))


# ---------------------------------------------------------------- summarize

META_HEADER = ("source", "run", "tier", "options", "head", "android_tree", "fingerprint", "scope")


def read_meta(path):
    meta = {}
    commands = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            key, sep, value = line.rstrip("\n").partition("=")
            if not sep:
                continue
            meta[key] = value
            if key.startswith("command") or key.startswith("exit."):
                commands.append((key, value))
    return meta, commands


def summarize(args):
    out = []
    checks = []
    changed = set()
    meta = {}
    if args.meta:
        meta, commands = read_meta(args.meta)
        for key in META_HEADER:
            if key in meta:
                out.append("%s: %s" % (key, meta[key]))
        for key, value in commands:
            out.append("%s: %s" % (key, value))
        if args.exit_code is None:
            args.exit_code = meta.get("exit")
        if args.since is None and meta.get("since"):
            args.since = float(meta["since"])
    if args.since is None:
        args.since = 0.0
    if args.changed:
        with open(args.changed, encoding="utf-8") as handle:
            changed = {line.strip() for line in handle if line.strip()}

    tasks = None
    if args.console:
        outcome, tasks, order = parse_console(args.console)
        exit_text = "" if args.exit_code is None else ", exit %s" % args.exit_code
        out.append("build: %s%s" % (outcome or "no BUILD line in console", exit_text))
        for task in order:
            state = tasks[task]
            if task.rsplit(":", 1)[-1].startswith(WATCHED_TASKS) and state in REUSED + ("FAILED",):
                out.append("task: %s %s" % (task, state))
        if outcome and outcome.startswith("BUILD SUCCESSFUL") and args.exit_code in (None, "0"):
            checks.append(("build", PASSED))
        else:
            checks.append(("build", "%s (%s%s)" % (FAILED, outcome or "no BUILD line", exit_text)))
    elif args.exit_code is not None:
        out.append("exit: %s" % args.exit_code)

    if args.junit:
        junit_since = args.since if args.junit_since is None else args.junit_since
        mode, since, reason = freshness(tasks, args.junit_task, junit_since)
        suites, stale, devices = parse_junit(args.junit, since)
        counts = Counter(status for _, _, cases in suites for status, _, _ in cases)
        total = sum(counts.values())
        label = "unit tests" if not devices else "instrumented tests"
        mismatch = []

        if suites:
            out.append("%s: %d tests in %d classes, %d failed, %d skipped"
                       % (label, total, len(suites), counts["FAIL"], counts["SKIP"]))
        else:
            out.append("%s: no results" % label)
        if stale:
            out.append("stale result files ignored: %d (older than since %d)" % (len(stale), since))
        if devices:
            out.append("devices: %s" % as_dict(devices))
            if args.expect_device:
                mismatch = sorted(d for d in devices if d != args.expect_device)
                if mismatch:
                    out.append("DEVICE MISMATCH: results from %s, expected only %s"
                               % (mismatch, args.expect_device))
        for _, _, cases in suites:
            for status, name, _ in cases:
                if status == "FAIL":
                    out.append("failed: %s" % name)
        if args.target_test:
            matches = [(status, name) for _, _, cases in suites for status, name, _ in cases
                       if fnmatch.fnmatchcase(name, args.target_test)]
            if not matches:
                out.append("target %s: NOT FOUND in results" % args.target_test)
            for status, name in matches:
                out.append("target %s: %s" % (name, status))
        if args.raw_out:
            write_raw(args.raw_out, suites, args.repo)
            out.append("raw listing: %s" % args.raw_out)

        task_failed = tasks is not None and tasks.get(args.junit_task) == "FAILED"
        if mismatch:
            status = "%s (results from another device)" % FAILED
        elif mode == "absent":
            status = "%s (%s)" % (NOT_RUN, reason)
        elif not suites:
            status = FAILED + " (task failed, no fresh results)" if task_failed \
                else "%s (no fresh results)" % NOT_RUN
        elif counts["FAIL"] or task_failed:
            status = "%s (%d failed)" % (FAILED, counts["FAIL"])
        elif mode == "reused":
            status = PASSED_UP_TO_DATE
        elif mode == "unknown":
            status = "%s (%s)" % (NEEDS_VERIFICATION, reason)
        else:
            status = PASSED
        checks.append((label, status))

    if args.lint:
        lint_since = args.since if args.lint_since is None else args.lint_since
        mode, since, reason = freshness(tasks, args.lint_task, lint_since)
        status = None
        if mode == "absent":
            status = "%s (%s)" % (NOT_RUN, reason)
        elif not os.path.isfile(args.lint):
            out.append("lint: report missing (%s)" % rel(args.lint, args.repo))
            status = "%s (report missing)" % NOT_RUN
        elif since and os.path.getmtime(args.lint) < since:
            out.append("lint: report older than since %d, ignored" % since)
            status = "%s (no fresh report)" % NOT_RUN
        else:
            severities, ids, in_changed = parse_lint(args.lint, args.repo, changed)
            out.append("lint severities: %s" % as_dict(severities))
            out.append("lint ids: %s" % as_dict(ids))
            if changed:
                out.append("lint in changed files: %s" % (", ".join(in_changed) or "none"))
            errors = severities["Error"] + severities["Fatal"]
            if errors:
                status = "%s (%d errors)" % (FAILED, errors)
            elif mode == "reused":
                status = PASSED_UP_TO_DATE
            elif mode == "unknown":
                status = "%s (%s)" % (NEEDS_VERIFICATION, reason)
            else:
                status = PASSED
        checks.append(("lint", status))

    if args.require_executed:
        if tasks is None:
            status = "%s (no console log)" % NOT_RUN
        else:
            missing = ["%s %s" % (task, tasks.get(task, "not in console"))
                       for task in args.require_executed
                       if tasks.get(task) not in ("EXECUTED", "FAILED")]
            if missing:
                status = "%s (not executed: %s)" % (FAILED, ", ".join(missing))
            else:
                status = "%s (%d forced tasks executed)" % (PASSED, len(args.require_executed))
        checks.append(("fresh", status))

    if args.meta:
        before, after = meta.get("fingerprint"), meta.get("fingerprint_after")
        if not before or not after or after == "unknown":
            status = "%s (fingerprint not recorded before and after the run)" % NEEDS_VERIFICATION
        elif before == after:
            status = "%s (unchanged during the run)" % PASSED
        else:
            status = "%s (changed during the run: %s -> %s)" % (FAILED, before, after)
        checks.append(("fingerprint", status))

    for name, status in checks:
        out.append("check %s: %s" % (name, status))
    text = scrub("\n".join(out), args.repo)
    for path in args.out:
        write_new(path, text + "\n", args.repo)
    print(text)


SUMMARY_FOLDERS = ("docs/android-audit/evidence", "docs/android-audit/reports/verify")


def write_new(path, text, repo, repo_allowed=SUMMARY_FOLDERS):
    """Create a new file: in a temporary directory, or inside the repository
    only under repo_allowed. Never overwrites (see .claude/tools/safe_paths.py)."""
    try:
        safe_paths.write_new(path, text, repo, repo_allowed)
    except ValueError as error:
        sys.exit(str(error))


def write_raw(path, suites, repo):
    lines = []
    for name, device, cases in suites:
        counts = Counter(status for status, _, _ in cases)
        simple = (name or "?").rsplit(".", 1)[-1]
        where = " device=%s" % device if device else ""
        lines.append("%s: tests=%d failures=%d skipped=%d%s"
                     % (simple, len(cases), counts["FAIL"], counts["SKIP"], where))
        for status, case, detail in cases:
            lines.append("  %s %s" % (status, case))
            for extra in detail or []:
                lines.append("       %s" % extra)
        lines.append("")
    # The raw listing goes to the scratchpad; sanitize copies it into evidence/.
    write_new(path, scrub("\n".join(lines), repo), repo, repo_allowed=())


# ---------------------------------------------------------------- compare

UNIT_LINE = re.compile(r"^(?:unit|instrumented) tests: (\d+) tests in (\d+) classes, (\d+) failed, (\d+) skipped")
CHECK_LINE = re.compile(r"^check ([^:]+): (.*)$")


def read_summary(path):
    tests, severities, ids = None, None, None
    checks = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            match = UNIT_LINE.match(line)
            if match:
                tests = tuple(int(value) for value in match.groups())
            match = CHECK_LINE.match(line)
            if match:
                checks[match.group(1)] = match.group(2)
            for key in ("lint severities:", "lint ids:"):
                if line.startswith(key):
                    found = re.search(r"\{.*?\}", line)
                    if found:
                        value = ast.literal_eval(found.group(0))
                        if key == "lint ids:":
                            ids = value
                        else:
                            severities = value
    return tests, severities, ids, checks


def compare(args):
    base_tests, base_sev, base_ids, base_checks = read_summary(args.baseline)
    cur_tests, cur_sev, cur_ids, cur_checks = read_summary(args.current)
    out = ["baseline: %s" % args.baseline]
    if base_tests and cur_tests:
        out.append("tests: %d -> %d (%+d); failed %d -> %d"
                   % (base_tests[0], cur_tests[0], cur_tests[0] - base_tests[0],
                      base_tests[2], cur_tests[2]))
    else:
        out.append("tests: not comparable (missing in %s)"
                   % ("baseline" if not base_tests else "current"))
    if base_sev is not None and cur_sev is not None:
        out.append("lint severities: %s -> %s" % (base_sev, cur_sev))
    if base_ids is not None and cur_ids is not None:
        deltas = []
        for key in sorted(set(base_ids) | set(cur_ids)):
            diff = cur_ids.get(key, 0) - base_ids.get(key, 0)
            if diff:
                deltas.append("%s %+d" % (key, diff))
        out.append("lint id changes: %s" % (", ".join(deltas) or "none"))
    else:
        out.append("lint: not comparable")
    if base_checks or cur_checks:
        for name in sorted(set(base_checks) | set(cur_checks)):
            before = base_checks.get(name, "not recorded")
            after = cur_checks.get(name, "not recorded")
            if before == after:
                out.append("check %s: %s (unchanged)" % (name, after))
            else:
                out.append("check %s: %s -> %s" % (name, before, after))
    print("\n".join(out))


# ---------------------------------------------------------------- sanitize

def sanitize(args):
    with open(args.inp, encoding="utf-8", errors="replace") as handle:
        text = handle.read()
    write_new(args.out, scrub(text, args.repo), args.repo, repo_allowed=("docs/android-audit/evidence",))
    print("wrote %s" % rel(os.path.abspath(args.out), args.repo))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    s = sub.add_parser("summarize")
    s.add_argument("--repo", required=True)
    s.add_argument("--console")
    s.add_argument("--exit-code")
    s.add_argument("--junit", action="append", default=[])
    s.add_argument("--junit-task", default=":app:testDebugUnitTest")
    s.add_argument("--junit-since", type=float)
    s.add_argument("--lint")
    s.add_argument("--lint-task", default=":app:lintReportDebug")
    s.add_argument("--lint-since", type=float)
    s.add_argument("--since", type=float)
    s.add_argument("--meta")
    s.add_argument("--out", action="append", default=[])
    s.add_argument("--changed")
    s.add_argument("--expect-device")
    s.add_argument("--target-test")
    s.add_argument("--raw-out")
    s.add_argument("--require-executed", action="append", default=[])

    c = sub.add_parser("compare")
    c.add_argument("--baseline", required=True)
    c.add_argument("--current", required=True)

    z = sub.add_parser("sanitize")
    z.add_argument("--repo", required=True)
    z.add_argument("inp")
    z.add_argument("out")

    args = parser.parse_args()
    try:
        check_inputs(args)
    except ValueError as error:
        sys.exit(str(error))
    {"summarize": summarize, "compare": compare, "sanitize": sanitize}[args.command](args)


def check_inputs(args):
    """The parser acts only on the repository that contains it, and reads only
    the repository and temporary directories. sanitize copies only a raw
    listing from a temporary directory, so it cannot carry an arbitrary file
    into the committable evidence folder."""
    root = safe_paths.script_root(__file__)
    if args.command == "compare":
        for path in (args.baseline, args.current):
            safe_paths.check_readable(path, root)
        return
    args.repo = safe_paths.check_repo(args.repo, __file__)
    if args.command == "sanitize":
        real = os.path.realpath(args.inp)
        if not safe_paths.in_temp(real) or safe_paths._under(real, args.repo) or not real.endswith("-raw.txt"):
            raise ValueError("refusing to sanitize %s: only a *-raw.txt listing from a temporary directory"
                             % args.inp)
        return
    for path in [args.console, args.meta, args.changed, args.lint] + list(args.junit):
        if path:
            safe_paths.check_readable(path, args.repo)


if __name__ == "__main__":
    main()
