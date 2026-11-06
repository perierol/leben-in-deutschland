"""Tests for safe_paths.py and git_read.py.

Run with:  python3 -I -B -m unittest discover -s .claude/tools/tests -v
The scripts only act on the repository that contains them, so git tests copy
.claude/tools into a temporary repository and run that copy. No test writes
outside a temporary directory: refusal cases check that the target was never
created.
"""

import importlib.util
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
REAL_GIT_READ = os.path.join(TOOLS, "git_read.py")

_spec = importlib.util.spec_from_file_location("safe_paths", os.path.join(TOOLS, "safe_paths.py"))
safe_paths = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(safe_paths)

HOME_TARGET = os.path.join(os.path.expanduser("~"), "claude-tools-must-not-exist.txt")


def install_tools(repo):
    """Copy .claude/tools into repo, so the copy may act on repo."""
    shutil.copytree(TOOLS, os.path.join(repo, ".claude", "tools"),
                    ignore=shutil.ignore_patterns("__pycache__", "tests"))
    return os.path.join(repo, ".claude", "tools", "git_read.py")


def marker_script(directory, name):
    """A script that creates <directory>/<name>-ran when it runs."""
    script = os.path.join(directory, name + ".sh")
    with open(script, "w", encoding="utf-8") as handle:
        handle.write("#!/bin/sh\ntouch '%s'\ncat\n" % os.path.join(directory, name + "-ran"))
    os.chmod(script, 0o755)
    return script, os.path.join(directory, name + "-ran")


class SafePathsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="claude-tools-")
        self.repo = os.path.join(self.tmp, "repo")
        os.makedirs(self.repo)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def test_new_file_in_private_temporary_subfolder(self):
        path = os.path.join(self.tmp, "sub", "out.txt")
        safe_paths.write_new(path, "x")
        with open(path, encoding="utf-8") as handle:
            self.assertEqual(handle.read(), "x")
        self.assertEqual(stat.S_IMODE(os.stat(os.path.dirname(path)).st_mode), 0o700)

    def test_home_directory_is_refused(self):
        with self.assertRaisesRegex(ValueError, "outside the temporary directories"):
            safe_paths.write_new(HOME_TARGET, "x")
        self.assertFalse(os.path.lexists(HOME_TARGET))

    def test_directly_in_a_temp_root_is_refused(self):
        target = os.path.join(safe_paths.temp_roots()[0], "claude-tools-must-not-exist.txt")
        with self.assertRaisesRegex(ValueError, "directly in"):
            safe_paths.write_new(target, "x")
        self.assertFalse(os.path.lexists(target))

    def test_folder_writable_by_others_is_refused(self):
        shared = os.path.join(self.tmp, "shared")
        os.mkdir(shared)
        os.chmod(shared, 0o777)
        with self.assertRaisesRegex(ValueError, "not a private folder"):
            safe_paths.write_new(os.path.join(shared, "x.txt"), "x")
        self.assertEqual(os.listdir(shared), [])

    def test_tmpdir_variable_cannot_widen_the_temp_roots(self):
        saved = os.environ.get("TMPDIR")
        os.environ["TMPDIR"] = os.path.expanduser("~")
        try:
            self.assertNotIn(os.path.realpath(os.path.expanduser("~")), safe_paths.temp_roots())
            with self.assertRaisesRegex(ValueError, "outside the temporary directories"):
                safe_paths.write_new(HOME_TARGET, "x")
        finally:
            if saved is None:
                del os.environ["TMPDIR"]
            else:
                os.environ["TMPDIR"] = saved
        self.assertFalse(os.path.lexists(HOME_TARGET))

    def test_symlink_from_temp_to_home_is_refused(self):
        link = os.path.join(self.tmp, "home-link")
        os.symlink(os.path.expanduser("~"), link)
        with self.assertRaisesRegex(ValueError, "outside the temporary directories"):
            safe_paths.write_new(os.path.join(link, "claude-tools-must-not-exist.txt"), "x")
        self.assertFalse(os.path.lexists(HOME_TARGET))

    def test_existing_file_and_dangling_symlink_are_never_overwritten(self):
        path = os.path.join(self.tmp, "sub", "exists.txt")
        safe_paths.write_new(path, "first")
        with self.assertRaisesRegex(ValueError, "refusing to overwrite"):
            safe_paths.write_new(path, "second")
        dangling = os.path.join(self.tmp, "sub", "dangling")
        os.symlink(os.path.join(self.tmp, "nowhere"), dangling)
        with self.assertRaisesRegex(ValueError, "refusing to overwrite"):
            safe_paths.write_new(dangling, "x")
        self.assertFalse(os.path.lexists(os.path.join(self.tmp, "nowhere")))

    def test_folder_swapped_for_a_symlink_while_creating_is_refused(self):
        outside = tempfile.mkdtemp(prefix="claude-tools-outside-")
        self.addCleanup(shutil.rmtree, outside)
        target = os.path.join(self.tmp, "late", "x.txt")
        real_mkdir = os.mkdir

        def swapping_mkdir(path, mode=0o777):
            os.symlink(outside, path)  # an attacker wins the race

        os.mkdir = swapping_mkdir
        try:
            with self.assertRaisesRegex(ValueError, "not a private folder"):
                safe_paths.write_new(target, "x")
        finally:
            os.mkdir = real_mkdir
        self.assertEqual(os.listdir(outside), [])

    def test_repository_only_under_allowed_folders(self):
        allowed = ("docs/android-audit/evidence",)
        ok = os.path.join(self.repo, "docs", "android-audit", "evidence", "F-99", "a.txt")
        safe_paths.write_new(ok, "x", self.repo, allowed)
        self.assertTrue(os.path.isfile(ok))
        with self.assertRaisesRegex(ValueError, "inside the repository, only docs/android-audit/evidence/ allowed"):
            safe_paths.write_new(os.path.join(self.repo, "android", "a.txt"), "x", self.repo, allowed)
        with self.assertRaisesRegex(ValueError, "inside the repository$"):
            safe_paths.write_new(os.path.join(self.repo, "a.txt"), "x", self.repo, ())

    def test_dot_dot_cannot_escape_an_allowed_folder(self):
        sneaky = os.path.join(self.repo, "docs", "android-audit", "evidence", "..", "..", "..", "android", "a.txt")
        with self.assertRaisesRegex(ValueError, "inside the repository"):
            safe_paths.write_new(sneaky, "x", self.repo, ("docs/android-audit/evidence",))

    def test_check_repo_accepts_only_the_containing_repository(self):
        script = install_tools(self.repo)
        self.assertEqual(safe_paths.check_repo(self.repo, script), os.path.realpath(self.repo))
        other = os.path.join(self.tmp, "other")
        os.makedirs(other)
        for repo in (other, os.path.join(self.repo, ".claude"), os.path.expanduser("~")):
            with self.assertRaisesRegex(ValueError, "this script only acts on"):
                safe_paths.check_repo(repo, script)

    def test_check_readable(self):
        self.assertTrue(safe_paths.check_readable(os.path.join(self.repo, "x"), self.repo))
        with self.assertRaisesRegex(ValueError, "refusing to read"):
            safe_paths.check_readable(os.path.expanduser("~/.config/gh/hosts.yml"), self.repo)


class GitReadTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="claude-git-read-")
        self.repo = os.path.join(self.tmp, "repo")
        os.makedirs(self.repo)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Test")
        self.git_read = install_tools(self.repo)
        self.write("android/A.kt", "class A\n")
        self.write("docs/a.md", "a\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "initial")
        self.first = self.git("rev-parse", "HEAD")
        self.write("android/A.kt", "class A2\n")
        self.git("commit", "-q", "-am", "second")

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def git(self, *args):
        return subprocess.run(["git", "-C", self.repo] + list(args), check=True,
                              capture_output=True, text=True).stdout.strip()

    def write(self, path, text):
        full = os.path.join(self.repo, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as handle:
            handle.write(text)

    def run_tool(self, *args, out=None, script=None, repo=None, env=None):
        prefix = ["--repo", repo or self.repo] + (["--out", out] if out else [])
        return subprocess.run([sys.executable, "-I", "-B", script or self.git_read] + prefix + list(args),
                              capture_output=True, text=True, env=env)

    def ok(self, *args):
        proc = self.run_tool(*args)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return proc.stdout

    def test_read_only_subcommands(self):
        self.write("new.txt", "n\n")
        self.assertEqual(self.ok("branch").strip(), "main")
        self.assertEqual(self.ok("head").strip(), self.git("rev-parse", "HEAD"))
        self.assertEqual(self.ok("status"), "?? new.txt\n")
        self.assertEqual(self.ok("staged"), "")
        self.assertEqual(self.ok("diff", self.first, "HEAD", "--name-only").split(), ["android/A.kt"])
        self.assertIn("android/A.kt", self.ok("diff", self.first + "...HEAD", "--stat"))
        self.assertEqual(self.ok("log", self.first + "..HEAD", "--oneline").count("\n"), 1)
        self.assertIn("second", self.ok("show-stat", "HEAD"))
        self.assertIn("android/A.kt", self.ok("last-commit", "android"))
        self.assertEqual(self.ok("merge-base", "main", "HEAD").strip(), self.git("rev-parse", "HEAD"))

    def test_diff_worktree_and_paths(self):
        self.write("docs/a.md", "changed\n")
        self.write("android/A.kt", "class A3\n")
        self.assertEqual(self.ok("diff-worktree", "--name-only", "--", "docs").split(), ["docs/a.md"])

    def test_branch_exists(self):
        self.assertEqual(self.run_tool("branch-exists", "main").returncode, 0)
        self.assertNotEqual(self.run_tool("branch-exists", "chore/missing").returncode, 0)

    def test_writing_and_deleting_options_are_refused(self):
        for args in (["diff", "--output=/tmp/x", "HEAD"], ["diff", "-D"], ["log", "--output=x"],
                     ["diff", "HEAD;rm"], ["rev-parse", "-q"], ["branch-exists", "-D"],
                     ["log", "HEAD", "--format=%H"], ["status", "--porcelain"], ["delete", "main"],
                     ["last-commit", "--output=x"], ["head", "--", "x"]):
            proc = self.run_tool(*args)
            self.assertNotEqual(proc.returncode, 0, args)
        self.assertEqual(self.git("branch", "--list", "main"), "* main")

    def test_foreign_repository_is_refused(self):
        # The real script may act only on the real repository, not on this one.
        proc = self.run_tool("status", script=REAL_GIT_READ)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("this script only acts on", proc.stderr)

    def test_external_diff_driver_is_never_run(self):
        script, ran = marker_script(self.tmp, "external")
        self.git("config", "diff.external", script)
        self.ok("diff", self.first, "HEAD")
        self.ok("log", self.first + "..HEAD")
        self.ok("show-stat", "HEAD")
        self.assertFalse(os.path.exists(ran))

    def test_fsmonitor_is_never_run(self):
        script, ran = marker_script(self.tmp, "fsmonitor")
        self.git("config", "core.fsmonitor", script)
        self.write("docs/a.md", "changed\n")
        self.ok("status")
        self.ok("diff-worktree", "--name-only")
        self.assertFalse(os.path.exists(ran))

    def test_git_environment_variables_are_ignored(self):
        other = os.path.join(self.tmp, "other")
        os.makedirs(other)
        subprocess.run(["git", "-C", other, "init", "-q"], check=True)
        env = dict(os.environ, GIT_DIR=os.path.join(other, ".git"), GIT_WORK_TREE=other)
        proc = self.run_tool("head", env=env)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(proc.stdout.strip(), self.git("rev-parse", "HEAD"))

    def test_out_writes_only_new_files_in_private_temporary_folders(self):
        out = os.path.join(self.tmp, "out", "diff.txt")
        proc = self.run_tool("diff", self.first, "HEAD", out=out)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        with open(out, encoding="utf-8") as handle:
            self.assertIn("class A2", handle.read())
        self.assertIn("refusing to overwrite", self.run_tool("status", out=out).stderr)
        self.assertIn("outside the temporary directories", self.run_tool("status", out=HOME_TARGET).stderr)
        self.assertFalse(os.path.lexists(HOME_TARGET))


if __name__ == "__main__":
    unittest.main(verbosity=2)
