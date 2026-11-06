"""Tests for fingerprint.py and the parser's --meta / --out handling.

Run with the rest of the suite:
  python3 -I -m unittest discover -s .claude/skills/android-verify/tests -v
Every test works in a temporary git repository or directory. Nothing starts Gradle.
"""

import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SKILL = os.path.dirname(HERE)
PARSER = os.path.join(SKILL, "parse_reports.py")
FIXTURES = os.path.join(HERE, "fixtures")
TOOLS = os.path.join(os.path.dirname(os.path.dirname(SKILL)), "tools")


def install_verify(root):
    """Copy android-verify and .claude/tools into root; the scripts act only on
    the repository that contains them."""
    skill = os.path.join(root, ".claude", "skills", "android-verify")
    shutil.copytree(SKILL, skill, ignore=shutil.ignore_patterns("__pycache__", "test_*.py"))
    shutil.copytree(TOOLS, os.path.join(root, ".claude", "tools"), ignore=shutil.ignore_patterns("__pycache__", "tests"))
    return skill


def marker_script(directory, name):
    script = os.path.join(directory, name + ".sh")
    with open(script, "w", encoding="utf-8") as handle:
        handle.write("#!/bin/sh\ntouch '%s'\n" % os.path.join(directory, name + "-ran"))
    os.chmod(script, 0o755)
    return script, os.path.join(directory, name + "-ran")

spec = importlib.util.spec_from_file_location("fingerprint", os.path.join(SKILL, "fingerprint.py"))
fingerprint = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fingerprint)


class GitRepoCase(unittest.TestCase):
    def setUp(self):
        self.repo = tempfile.mkdtemp(prefix="android-verify-fp-")
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Test")
        self.write("android/app/src/main/A.kt", "class A\n")
        self.write("docs/notes.md", "notes\n")
        self.write(".gitignore", "android/local.properties\nandroid/app/build/\n")
        self.commit("initial")

    def tearDown(self):
        shutil.rmtree(self.repo)

    def git(self, *args):
        return subprocess.run(["git", "-C", self.repo] + list(args), check=True,
                              capture_output=True, text=True).stdout.strip()

    def write(self, path, text):
        full = os.path.join(self.repo, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as handle:
            handle.write(text)

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")


class ComputeTest(GitRepoCase):
    def test_clean_tree(self):
        result = fingerprint.compute(self.repo)
        self.assertEqual(result["android_tree"], "clean")
        self.assertEqual(result["head"], self.git("rev-parse", "HEAD"))
        self.assertEqual(result["fingerprint"], result["head"][:12] + ":clean")
        self.assertEqual(result["scope"], "android .github/workflows/android-ci.yml")

    def test_tracked_change_is_hashed_the_same_staged_or_not(self):
        self.write("android/app/src/main/A.kt", "class A { val x = 1 }\n")
        unstaged = fingerprint.compute(self.repo)["android_tree"]
        self.git("add", "android/app/src/main/A.kt")
        staged = fingerprint.compute(self.repo)["android_tree"]
        self.assertNotEqual(unstaged, "clean")
        self.assertEqual(unstaged, staged)

    def test_untracked_android_file_counts_and_its_content_matters(self):
        self.write("android/app/src/main/B.kt", "class B\n")
        first = fingerprint.compute(self.repo)["android_tree"]
        self.write("android/app/src/main/B.kt", "class B2\n")
        second = fingerprint.compute(self.repo)["android_tree"]
        self.assertNotEqual(first, "clean")
        self.assertNotEqual(first, second)

    def test_ignored_and_out_of_scope_changes_do_not_count(self):
        self.write("android/local.properties", "sdk.dir=/somewhere\n")
        self.write("android/app/build/out.txt", "build output\n")
        self.write("docs/notes.md", "changed notes\n")
        self.write(".claude/x.md", "skill\n")
        self.assertEqual(fingerprint.compute(self.repo)["android_tree"], "clean")

    def test_ci_workflow_is_in_scope(self):
        self.write(".github/workflows/android-ci.yml", "name: Android CI\n")
        self.assertNotEqual(fingerprint.compute(self.repo)["android_tree"], "clean")

    def test_cli_prints_key_value_lines(self):
        skill = install_verify(self.repo)
        proc = subprocess.run([sys.executable, "-I", "-B", os.path.join(skill, "fingerprint.py"), "--repo", self.repo],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        keys = [line.split("=", 1)[0] for line in proc.stdout.splitlines()]
        self.assertEqual(keys, ["head", "android_tree", "fingerprint", "scope"])

    def test_cli_refuses_a_foreign_repository(self):
        # SEC-4: the real script may act only on the real repository.
        proc = subprocess.run([sys.executable, "-I", "-B", os.path.join(SKILL, "fingerprint.py"), "--repo", self.repo],
                              capture_output=True, text=True)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("this script only acts on", proc.stderr)

    def test_external_diff_and_textconv_never_run_or_hide_changes(self):
        # SEC-3: a configured diff tool must not run, and must not make a change look clean.
        tools = tempfile.mkdtemp(prefix="android-verify-fp-tools-")
        self.addCleanup(shutil.rmtree, tools)
        external, external_ran = marker_script(tools, "external")
        textconv, textconv_ran = marker_script(tools, "textconv")
        fsmonitor, fsmonitor_ran = marker_script(tools, "fsmonitor")
        self.write(".gitattributes", "*.kt diff=kt\n")
        self.commit("attributes")
        self.write("android/app/src/main/A.kt", "class A { val changed = 1 }\n")
        # Set after the test's own git calls, so only fingerprint.py could run them.
        self.git("config", "diff.external", external)
        self.git("config", "diff.kt.textconv", textconv)
        self.git("config", "core.fsmonitor", fsmonitor)
        tree = fingerprint.compute(self.repo)["android_tree"]
        self.assertNotEqual(tree, "clean")
        for ran in (external_ran, textconv_ran, fsmonitor_ran):
            self.assertFalse(os.path.exists(ran), ran)


class IsCurrentTest(GitRepoCase):
    def evidence(self):
        result = fingerprint.compute(self.repo)
        return result["head"], result["android_tree"]

    def test_same_commit_and_tree_is_current(self):
        head, tree = self.evidence()
        self.assertEqual(fingerprint.is_current(self.repo, head, tree), (True, "same commit and working tree"))

    def test_docs_only_commit_keeps_evidence_current(self):
        head, tree = self.evidence()
        self.write("docs/notes.md", "more notes\n")
        self.commit("docs")
        current, reason = fingerprint.is_current(self.repo, head, tree)
        self.assertTrue(current)
        self.assertIn("no Android changes since", reason)

    def test_android_commit_makes_evidence_stale(self):
        head, tree = self.evidence()
        self.write("android/app/src/main/A.kt", "class A2\n")
        self.commit("android")
        current, reason = fingerprint.is_current(self.repo, head, tree)
        self.assertFalse(current)
        self.assertIn("1 Android file(s) changed since", reason)

    def test_uncommitted_android_change_makes_evidence_stale(self):
        head, tree = self.evidence()
        self.write("android/app/src/main/A.kt", "class A3\n")
        current, reason = fingerprint.is_current(self.repo, head, tree)
        self.assertFalse(current)
        self.assertIn("uncommitted Android changes differ", reason)

    def test_unknown_commit_is_stale(self):
        current, reason = fingerprint.is_current(self.repo, "0" * 40, "clean")
        self.assertFalse(current)
        self.assertIn("not in this repository", reason)


class MetaTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="android-verify-meta-")
        self.root = os.path.join(self.tmp, "repo")
        os.makedirs(self.root)
        skill = install_verify(self.root)
        self.parser = os.path.join(skill, "parse_reports.py")
        self.fixtures = os.path.join(skill, "tests", "fixtures")

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def meta(self, after):
        path = os.path.join(self.tmp, "run-meta.txt")
        lines = ["source=local android-verify", "run=20261106-120000-ci", "tier=ci", "options=",
                 "head=" + "a" * 40, "android_tree=clean", "fingerprint=aaaaaaaaaaaa:clean",
                 "scope=android .github/workflows/android-ci.yml", "since=1",
                 "command=android/gradlew -p android assembleDebug lintDebug testDebugUnitTest --stacktrace",
                 "exit=0"]
        if after is not None:
            lines.append("fingerprint_after=" + after)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")
        return path

    def summarize(self, meta, *extra, expect_ok=True):
        proc = subprocess.run([sys.executable, "-I", self.parser, "summarize", "--repo", self.root,
                               "--console", os.path.join(self.fixtures, "console-ci-executed.txt"),
                               "--meta", meta] + list(extra), capture_output=True, text=True)
        if expect_ok:
            self.assertEqual(proc.returncode, 0, proc.stderr)
        return proc

    def test_header_and_unchanged_fingerprint(self):
        out = self.summarize(self.meta("aaaaaaaaaaaa:clean")).stdout
        lines = out.splitlines()
        self.assertEqual(lines[0], "source: local android-verify")
        self.assertIn("head: " + "a" * 40, lines)
        self.assertIn("fingerprint: aaaaaaaaaaaa:clean", lines)
        self.assertIn("build: BUILD SUCCESSFUL, exit 0", lines)
        self.assertIn("check fingerprint: Passed (unchanged during the run)", lines)

    def test_changed_fingerprint_fails(self):
        out = self.summarize(self.meta("aaaaaaaaaaaa:0123456789abcdef")).stdout
        self.assertIn("check fingerprint: Failed (changed during the run: aaaaaaaaaaaa:clean -> "
                      "aaaaaaaaaaaa:0123456789abcdef)", out)

    def test_missing_after_needs_verification(self):
        out = self.summarize(self.meta(None)).stdout
        self.assertIn("check fingerprint: Needs verification", out)

    def test_out_writes_new_files_only_in_allowed_places(self):
        meta = self.meta("aaaaaaaaaaaa:clean")
        local = os.path.join(self.root, "docs", "android-audit", "reports", "verify", "run-summary.txt")
        scratch = os.path.join(self.tmp, "run-summary.txt")
        out = self.summarize(meta, "--out", scratch, "--out", local).stdout
        for path in (scratch, local):
            with open(path, encoding="utf-8") as handle:
                self.assertEqual(handle.read(), out)
        again = self.summarize(meta, "--out", local, expect_ok=False)
        self.assertIn("refusing to overwrite", again.stderr)
        inside = self.summarize(meta, "--out", os.path.join(self.root, "android", "x.txt"), expect_ok=False)
        self.assertIn("only docs/android-audit/evidence/", inside.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
