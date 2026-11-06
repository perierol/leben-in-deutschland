#!/usr/bin/env python3
"""Read-only git for the android-* skills.

A pre-approved permission rule such as `Bash(git diff *)` matches any arguments,
including ones that write (`git diff --output=<file>`) or delete
(`git branch -D`). The skills pre-approve this script instead. It offers a
fixed set of read-only subcommands, validates every revision and path, passes
arguments to git without a shell, and turns off external diff and textconv
drivers. The only file it writes is --out: a new file in a temporary directory
(see safe_paths.py).

Run with `python3 -I`. Compatible with Python 3.9.

  git_read.py --repo ROOT status            git status --porcelain=v1 --untracked-files=all
  git_read.py --repo ROOT staged            git diff --cached --name-only
  git_read.py --repo ROOT branch            git branch --show-current
  git_read.py --repo ROOT head [--short]    git rev-parse [--short] HEAD
  git_read.py --repo ROOT rev-parse REV     git rev-parse --verify REV^{commit}
  git_read.py --repo ROOT merge-base A B
  git_read.py --repo ROOT branch-exists NAME
  git_read.py --repo ROOT diff REV[...REV] [REV] [--name-only | --stat] [-- PATH...]
  git_read.py --repo ROOT diff-worktree [--name-only | --stat] [-- PATH...]   (uncommitted, vs HEAD)
  git_read.py --repo ROOT log RANGE [--max N] [--oneline] [--name-only] [-- PATH...]
  git_read.py --repo ROOT show-stat REV
  git_read.py --repo ROOT last-commit PATHSPEC   the newest commit touching PATHSPEC, with its files
`--out FILE`, before the subcommand, writes the output to a new file instead of stdout.
"""

import argparse
import importlib.util
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("safe_paths", os.path.join(HERE, "safe_paths.py"))
safe_paths = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(safe_paths)

REV = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9._/@^~-]*$")
BRANCH = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9._/-]*$")
# Diff, log and show output never runs a configured external program.
SAFE_DIFF = safe_paths.DIFF_SAFE_OPTIONS + ["--no-color"]
SAFE_LOG = SAFE_DIFF + ["--no-show-signature"]


def fail(message):
    sys.exit("git_read: %s" % message)


def rev(value):
    parts = re.split(r"\.\.\.?", value) if ".." in value else [value]
    if len(parts) > 2 or not all(REV.match(p) for p in parts):
        fail("invalid revision %r" % value)
    return value


def paths(values):
    for value in values:
        if value.startswith("-") or "\0" in value:
            fail("invalid path %r" % value)
    return values


def split_paths(argv):
    if "--" in argv:
        index = argv.index("--")
        return argv[:index], paths(argv[index + 1:])
    return argv, []


def build(args, rest):
    rest, pathspec = split_paths(rest)
    command = args.command
    if command in ("status", "staged", "branch") and (rest or pathspec):
        fail("%s takes no arguments" % command)
    if command == "head" and (rest not in ([], ["--short"]) or pathspec):
        fail("head takes only --short")
    if command == "status":
        return ["status", "--porcelain=v1", "--untracked-files=all"]
    if command == "staged":
        return ["diff", "--cached", "--name-only"]
    if command == "branch":
        return ["branch", "--show-current"]
    if command == "head":
        return ["rev-parse"] + (["--short"] if rest == ["--short"] else []) + ["HEAD"]
    if command == "rev-parse":
        if len(rest) != 1:
            fail("rev-parse takes one revision")
        return ["rev-parse", "--verify", "--quiet", rev(rest[0]) + "^{commit}"]
    if command == "merge-base":
        if len(rest) != 2:
            fail("merge-base takes two revisions")
        return ["merge-base", rev(rest[0]), rev(rest[1])]
    if command == "branch-exists":
        if len(rest) != 1 or not BRANCH.match(rest[0]):
            fail("branch-exists takes one branch name")
        return ["rev-parse", "--verify", "--quiet", "refs/heads/" + rest[0]]
    if command in ("diff", "diff-worktree"):
        mode = [flag for flag in rest if flag in ("--name-only", "--stat")]
        revs = [value for value in rest if value not in ("--name-only", "--stat")]
        if len(mode) > 1 or (command == "diff" and not 1 <= len(revs) <= 2) or (command == "diff-worktree" and revs):
            fail("usage: diff REV[...REV] [REV] [--name-only|--stat] [-- PATH...]")
        base = ["diff"] + SAFE_DIFF + mode + [rev(value) for value in revs]
        if command == "diff-worktree":
            base.append("HEAD")
        return base + ["--"] + pathspec
    if command == "log":
        if not rest:
            fail("log takes a range")
        command_args = ["log"] + SAFE_LOG
        values = list(rest)
        if "--max" in values:
            index = values.index("--max")
            if index + 1 >= len(values) or not values[index + 1].isdigit():
                fail("--max takes a number")
            command_args.append("--max-count=%s" % values[index + 1])
            del values[index:index + 2]
        for flag in ("--oneline", "--name-only"):
            if flag in values:
                command_args.append(flag)
                values.remove(flag)
        if len(values) != 1:
            fail("log takes one range")
        return command_args + [rev(values[0]), "--"] + pathspec
    if command == "show-stat":
        if len(rest) != 1:
            fail("show-stat takes one revision")
        return ["show"] + SAFE_LOG + ["--stat", "--format=%h %s%n%n%b", rev(rest[0])]
    if command == "last-commit":
        if len(rest) != 1 or rest[0].startswith("-"):
            fail("last-commit takes one pathspec")
        return ["log"] + SAFE_LOG + ["--max-count=1", "--name-only", "--format=%h %cs", "--", rest[0]]
    fail("unknown subcommand %r" % command)


def main():
    # Parsed by hand: --repo and --out come before the subcommand, and everything
    # after it is passed to build() unchanged, including "--".
    argv = sys.argv[1:]
    if argv[:1] in (["-h"], ["--help"]):
        print(__doc__)
        sys.exit(0)
    args = argparse.Namespace(repo=None, out=None, command=None)
    while argv and argv[0] in ("--repo", "--out"):
        if len(argv) < 2:
            fail("%s takes a value" % argv[0])
        setattr(args, argv[0][2:], argv[1])
        argv = argv[2:]
    if not args.repo or not argv:
        fail("usage: git_read.py --repo ROOT [--out FILE] SUBCOMMAND [ARGS]")
    args.command, rest = argv[0], argv[1:]
    try:
        root = safe_paths.check_repo(args.repo, __file__)
    except ValueError as error:
        fail(str(error))
    git_args = build(args, rest)
    proc = safe_paths.run_git(root, *git_args)
    if args.out:
        try:
            safe_paths.write_new(args.out, proc.stdout.decode("utf-8", errors="replace"))
        except ValueError as error:
            fail(str(error))
        print("wrote %s (%d bytes)" % (args.out, len(proc.stdout)))
    else:
        sys.stdout.write(proc.stdout.decode("utf-8", errors="replace"))
    sys.stderr.write(proc.stderr.decode("utf-8", errors="replace"))
    sys.exit(proc.returncode)


if __name__ == "__main__":
    main()
