#!/usr/bin/env python3
"""Verification fingerprint for android-verify and android-pr.

A fingerprint names the code a local verification ran against:

  head          the commit (`git rev-parse HEAD`)
  android_tree  "clean", or a short sha256 over the uncommitted changes in the
                verification scope: `git diff --binary HEAD` (staged and
                unstaged) plus the path and contents of each untracked,
                non-ignored file
  scope         the paths that can change Android CI's result:
                android/ and .github/workflows/android-ci.yml

Ignored files (local.properties, google-services.json, build outputs) are not
part of the fingerprint.

Evidence is current for the code now when its android_tree matches, and
`git diff <evidence head> <current head> -- <scope>` is empty, so a commit that
only touches docs does not make it stale.

Run with `python3 -I`. Compatible with Python 3.9. Read-only. Git runs with the
options in .claude/tools/safe_paths.py, so configured external diff and
textconv programs never run and cannot change the hash. The command line only
accepts the repository that contains this script.

  fingerprint.py --repo ROOT        prints head=, android_tree=, fingerprint=, scope=
"""

import argparse
import hashlib
import importlib.util
import os
import re
import sys

_spec = importlib.util.spec_from_file_location(
    "safe_paths", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tools", "safe_paths.py"))
safe_paths = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(safe_paths)

SCOPE = ("android", ".github/workflows/android-ci.yml")


def git(repo, *args):
    proc = safe_paths.run_git(repo, *args)
    if proc.returncode != 0:
        raise RuntimeError("git %s failed: %s" % (" ".join(args), proc.stderr.decode().strip()))
    return proc.stdout


def compute(repo):
    head = git(repo, "rev-parse", "HEAD").decode().strip()
    diff = git(repo, "diff", "--binary", *safe_paths.DIFF_SAFE_OPTIONS, "HEAD", "--", *SCOPE)
    untracked = sorted(p for p in git(repo, "ls-files", "--others", "--exclude-standard", "-z",
                                      "--", *SCOPE).decode().split("\0") if p)
    if not diff and not untracked:
        tree = "clean"
    else:
        digest = hashlib.sha256(diff)
        for path in untracked:
            digest.update(b"\0untracked\0" + path.encode() + b"\0")
            with open(os.path.join(repo, path), "rb") as handle:
                digest.update(hashlib.sha256(handle.read()).digest())
        tree = digest.hexdigest()[:16]
    return {"head": head, "android_tree": tree, "fingerprint": "%s:%s" % (head[:12], tree),
            "scope": " ".join(SCOPE)}


def is_current(repo, evidence_head, evidence_tree, current=None):
    """Return (current?, reason) for evidence recorded at evidence_head/evidence_tree."""
    current = current or compute(repo)
    if evidence_tree != current["android_tree"]:
        return False, "uncommitted Android changes differ (%s now, %s at verification)" % (
            current["android_tree"], evidence_tree)
    if evidence_head == current["head"]:
        return True, "same commit and working tree"
    # evidence_head comes from a summary file: only a hex commit ID reaches git.
    if not re.fullmatch(r"[0-9a-f]{7,40}", evidence_head):
        return False, "verified commit %r is not a commit ID" % evidence_head[:40]
    exists = safe_paths.run_git(repo, "cat-file", "-e", evidence_head + "^{commit}").returncode == 0
    if not exists:
        return False, "verified commit %s is not in this repository" % evidence_head[:12]
    changed = git(repo, "diff", "--name-only", *safe_paths.DIFF_SAFE_OPTIONS,
                  evidence_head, current["head"], "--", *SCOPE).decode().split()
    if changed:
        return False, "%d Android file(s) changed since %s" % (len(changed), evidence_head[:12])
    return True, "no Android changes since %s" % evidence_head[:12]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", required=True)
    args = parser.parse_args()
    try:
        repo = safe_paths.check_repo(args.repo, __file__)
    except ValueError as error:
        sys.exit(str(error))
    for key, value in compute(repo).items():
        print("%s=%s" % (key, value))


if __name__ == "__main__":
    main()
