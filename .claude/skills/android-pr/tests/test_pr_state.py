"""Tests for pr_state.py.

Run with:  python3 -I -m unittest discover -s .claude/skills/android-pr/tests -v
Each test builds a temporary git repository. Nothing touches the real repository,
the network or GitHub.
"""

import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SKILL = os.path.dirname(HERE)
REAL_PR_STATE = os.path.join(SKILL, "pr_state.py")
SKILLS = os.path.dirname(SKILL)
TOOLS = os.path.join(os.path.dirname(SKILLS), "tools")


def install(repo):
    """Copy android-pr, android-verify and .claude/tools into repo. The scripts
    act only on the repository that contains them, so each test runs a copy."""
    ignore = shutil.ignore_patterns("__pycache__", "tests")
    for name in ("android-pr", "android-verify"):
        shutil.copytree(os.path.join(SKILLS, name), os.path.join(repo, ".claude", "skills", name), ignore=ignore)
    shutil.copytree(TOOLS, os.path.join(repo, ".claude", "tools"), ignore=ignore)
    return (os.path.join(repo, ".claude", "skills", "android-pr", "pr_state.py"),
            os.path.join(repo, ".claude", "skills", "android-verify", "fingerprint.py"))


def marker_script(directory, name):
    script = os.path.join(directory, name + ".sh")
    with open(script, "w", encoding="utf-8") as handle:
        handle.write("#!/bin/sh\ntouch '%s'\n" % os.path.join(directory, name + "-ran"))
    os.chmod(script, 0o755)
    return script, os.path.join(directory, name + "-ran")

# Assembled at runtime so this file does not contain a secret marker itself.
PRIVATE_KEY_LINE = "-----BEGIN " + "PRIVATE KEY-----\n"


class RepoCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="android-pr-test-")
        self.repo = os.path.join(self.tmp, "repo")
        os.makedirs(self.repo)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Test")
        self.write("android/app/src/main/A.kt", "class A\n")
        self.write("docs/android-audit/ai-workflow.md", "workflow\n")
        self.write(".gitignore", "android/local.properties\n")
        self.pr_state, self.fingerprint = install(self.repo)
        self.commit("initial")

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

    def commit(self, message, *paths):
        self.git("add", *(paths or ("-A",)))
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")

    def state(self, *args):
        proc = subprocess.run([sys.executable, "-I", self.pr_state, "state", "--repo", self.repo] + list(args),
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return proc.stdout.splitlines()

    def line(self, lines, prefix):
        found = [l for l in lines if l.startswith(prefix)]
        self.assertEqual(len(found), 1, "\n".join(lines))
        return found[0]

    def gate(self, lines, name):
        return self.line(lines, "gate %s: " % name)[len("gate %s: " % name):]

    def files(self, lines, cls):
        return [l.split(" ", 3)[3] for l in lines if l.startswith("file %s " % cls)]

    def summary(self, name, extra=""):
        fp = subprocess.run([sys.executable, "-I", self.fingerprint, "--repo", self.repo],
                            capture_output=True, text=True, check=True).stdout
        values = dict(line.split("=", 1) for line in fp.splitlines())
        directory = os.path.join(self.repo, "docs", "android-audit", "reports", "verify")
        os.makedirs(directory, exist_ok=True)
        with open(os.path.join(directory, name), "w", encoding="utf-8") as handle:
            handle.write("source: local android-verify\nrun: r\ntier: ci\noptions: %s\nhead: %s\n"
                         "android_tree: %s\nfingerprint: %s\n"
                         "check build: Passed\ncheck unit tests: Passed\ncheck lint: Passed\n"
                         "check fingerprint: Passed (unchanged during the run)\n%s"
                         % ("--fresh" if "fresh" in name else "", values["head"], values["android_tree"],
                            values["fingerprint"], extra))


class BranchAndStagingTest(RepoCase):
    def test_on_main_blocks(self):
        self.write(".claude/agents/a.md", "agent\n")
        lines = self.state("--include", ".claude/agents")
        self.assertTrue(self.gate(lines, "G1 branch").startswith("Blocked (on main"))
        self.assertEqual(self.line(lines, "readiness: "), "readiness: Blocked")

    def test_detached_head_blocks(self):
        self.git("checkout", "-q", "--detach")
        self.assertEqual(self.gate(self.state(), "G1 branch"), "Blocked (detached HEAD)")

    def test_prestaged_file_blocks(self):
        self.git("switch", "-q", "-c", "chore/x")
        self.write("notes.txt", "x\n")
        self.git("add", "notes.txt")
        lines = self.state("--include", "notes.txt")
        self.assertEqual(self.gate(lines, "G2 staging"), "Blocked (1 file(s) already staged)")

    def test_nothing_in_scope_blocks(self):
        self.git("switch", "-q", "-c", "chore/x")
        self.assertEqual(self.gate(self.state(), "G0 scope"), "Blocked (no committed or included files)")


class ClassificationTest(RepoCase):
    def setUp(self):
        super().setUp()
        self.git("switch", "-q", "-c", "chore/android-ai-workflow")
        for path in (".claude/agents/compose-reviewer.md", ".claude/skills/android-pr/SKILL.md",
                     ".claude/.DS_Store", ".claude/skills/android-pr/.DS_Store", ".agents/skills/goldie/SKILL.md",
                     ".argent/x.json", "goldie/config.json", "skills-lock.json",
                     "docs/android-audit/reports/2026-11-05-range.md", "scratch.txt"):
            self.write(path, "x\n")
        self.write(".gitignore", "android/local.properties\ngoldie/out/\n")
        self.write("docs/android-audit/ai-workflow.md", "workflow v2\n")

    def test_include_never_stage_and_unrelated(self):
        lines = self.state("--include", ".claude", "--include", "docs/android-audit/ai-workflow.md")
        self.assertEqual(sorted(self.files(lines, "pr")), [
            ".claude/agents/compose-reviewer.md", ".claude/skills/android-pr/SKILL.md",
            "docs/android-audit/ai-workflow.md"])
        self.assertEqual(sorted(self.files(lines, "never-stage")), [
            ".agents/skills/goldie/SKILL.md", ".argent/x.json", ".claude/.DS_Store",
            ".claude/skills/android-pr/.DS_Store", "docs/android-audit/reports/2026-11-05-range.md",
            "goldie/config.json", "skills-lock.json"])
        self.assertEqual(self.files(lines, "needs-approval"), [".gitignore"])
        self.assertEqual(self.files(lines, "unrelated"), ["scratch.txt"])

    def test_gitignore_only_when_included_by_exact_path(self):
        lines = self.state("--include", ".gitignore")
        self.assertIn(".gitignore", self.files(lines, "pr"))

    def test_files_out_lists_exact_paths_and_refuses_repo_or_overwrite(self):
        out = os.path.join(self.tmp, "pr-files.txt")
        self.state("--include", ".claude", "--files-out", out)
        with open(out, encoding="utf-8") as handle:
            self.assertEqual(sorted(p for p in handle.read().split("\0") if p), [
                ".claude/agents/compose-reviewer.md", ".claude/skills/android-pr/SKILL.md"])
        home_target = os.path.join(os.path.expanduser("~"), "android-pr-must-not-exist.txt")
        for target, message in ((out, "refusing to overwrite"),
                                (os.path.join(self.repo, "files.txt"), "inside the repository"),
                                (home_target, "outside the temporary directories")):
            proc = subprocess.run([sys.executable, "-I", self.pr_state, "state", "--repo", self.repo,
                                   "--include", ".claude", "--files-out", target], capture_output=True, text=True)
            self.assertNotEqual(proc.returncode, 0)
            self.assertIn(message, proc.stderr)
        self.assertFalse(os.path.lexists(home_target))

    def test_docs_and_skills_only_do_not_trigger_android_ci(self):
        lines = self.state("--include", ".claude", "--include", "docs/android-audit/ai-workflow.md")
        self.assertTrue(self.line(lines, "android ci: ").startswith("android ci: not triggered"))
        self.assertEqual(self.gate(lines, "G4 local verification"),
                         "Not required (no files in Android CI scope)")
        self.assertEqual(self.gate(lines, "G5 audit"), "Not required (no files android-audit reviews)")
        self.assertEqual(self.line(lines, "readiness: "), "readiness: Ready")


class SecretTest(RepoCase):
    def setUp(self):
        super().setUp()
        self.git("switch", "-q", "-c", "chore/x")

    def test_secret_file_name_blocks_even_when_not_included(self):
        self.write("android/app/google-services.json", "{}\n")
        lines = self.state()
        self.assertIn("android/app/google-services.json", self.files(lines, "secret-risk"))
        self.assertTrue(self.gate(lines, "G3 secrets").startswith("Blocked"))

    def test_marker_is_named_but_value_not_printed(self):
        self.write("tools/key.txt", PRIVATE_KEY_LINE + "c2VjcmV0dmFsdWU=\n")
        lines = self.state("--include", "tools")
        secrets = self.line(lines, "secrets: ")
        self.assertIn("tools/key.txt: private key block", secrets)
        self.assertNotIn("c2VjcmV0dmFsdWU", "\n".join(lines))
        self.assertTrue(self.gate(lines, "G3 secrets").startswith("Blocked"))

    def test_ignored_local_properties_is_not_reported(self):
        self.write("android/local.properties", "sdk.dir=/x\n")
        self.write("notes.md", "x\n")
        self.assertEqual(self.line(self.state("--include", "notes.md"), "secrets: "), "secrets: none found")


class VerificationEvidenceTest(RepoCase):
    def setUp(self):
        super().setUp()
        self.git("switch", "-q", "-c", "fix/android-x")
        self.write("android/app/src/main/A.kt", "class A { val fixed = true }\n")
        self.commit("fix")

    def test_android_change_without_evidence_blocks(self):
        lines = self.state()
        self.assertTrue(self.line(lines, "android ci: ").startswith("android ci: triggered"))
        self.assertEqual(self.gate(lines, "G4 local verification"),
                         "Blocked (no current passing android-verify ci summary)")

    def test_current_fresh_summary_passes(self):
        self.summary("20261106-120000-ci-fresh-summary.txt", "check fresh: Passed (5 forced tasks executed)\n")
        gate = self.gate(self.state(), "G4 local verification")
        self.assertTrue(gate.startswith("Passed (docs/android-audit/reports/verify/20261106-120000-ci-fresh"))
        self.assertTrue(gate.endswith("(fresh))"))

    def test_up_to_date_summary_passes_with_label(self):
        self.summary("20261106-120000-ci-summary.txt")
        path = os.path.join(self.repo, "docs/android-audit/reports/verify/20261106-120000-ci-summary.txt")
        with open(path, encoding="utf-8") as handle:
            text = handle.read().replace("check unit tests: Passed\n",
                                         "check unit tests: Passed (up to date, not executed in this run)\n")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        self.assertTrue(self.gate(self.state(), "G4 local verification").endswith("(partly up to date))"))

    def test_docs_only_commit_keeps_summary_current(self):
        self.summary("20261106-120000-ci-summary.txt")
        self.write("docs/android-audit/ai-workflow.md", "updated\n")
        self.commit("docs", "docs/android-audit/ai-workflow.md")
        lines = self.state()
        self.assertTrue(any(l.startswith("evidence current ci ") for l in lines), "\n".join(lines))
        self.assertTrue(self.gate(lines, "G4 local verification").startswith("Passed"))

    def test_android_change_after_verification_makes_it_stale(self):
        self.summary("20261106-120000-ci-summary.txt")
        self.write("android/app/src/main/A.kt", "class A { val again = true }\n")
        lines = self.state()
        self.assertTrue(any(l.startswith("evidence stale ci ") and "uncommitted Android changes differ" in l
                            for l in lines), "\n".join(lines))
        self.assertTrue(self.gate(lines, "G4 local verification").startswith("Blocked"))

    def test_failed_or_changed_fingerprint_summary_does_not_pass(self):
        self.summary("20261106-120000-ci-summary.txt")
        path = os.path.join(self.repo, "docs/android-audit/reports/verify/20261106-120000-ci-summary.txt")
        with open(path, encoding="utf-8") as handle:
            text = handle.read().replace("check fingerprint: Passed (unchanged during the run)",
                                         "check fingerprint: Failed (changed during the run: a -> b)")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        self.assertTrue(self.gate(self.state(), "G4 local verification").startswith("Blocked"))

    def test_legacy_summary_is_never_current(self):
        # Same name and format as the committed F-04 evidence: no fingerprint.
        self.write("docs/android-audit/evidence/F-04/green-ci-reports-summary.txt",
                   "build: BUILD SUCCESSFUL, exit 0\nunit tests: 86 tests in 17 classes, 0 failed, 0 skipped\n")
        lines = self.state()
        self.assertTrue(any(l.startswith("evidence legacy ? docs/android-audit/evidence/F-04/"
                                         "green-ci-reports-summary.txt") for l in lines), "\n".join(lines))
        self.assertTrue(self.gate(lines, "G4 local verification").startswith("Blocked"))

    def test_ui_change_marks_instrumented_unverified(self):
        self.write("android/app/src/main/java/x/feature/home/ui/HomeScreen.kt", "fun Home() {}\n")
        self.commit("ui")
        self.summary("20261106-120000-ci-summary.txt")
        lines = self.state()
        self.assertEqual(self.gate(lines, "G4b instrumented"), "Unverified (no current instrumented run)")
        self.assertEqual(self.line(lines, "readiness: "), "readiness: Ready with unverified items")


class AuditTest(RepoCase):
    def report(self, commit, regressions="None found."):
        self.write("docs/android-audit/reports/2026-11-06-diff.md",
                   "# Android audit\n\n## 1. Header\n| Field | Value |\n|---|---|\n"
                   "| Branch / commit | fix/android-x @ %s |\n| Scope | diff: main...HEAD |\n\n"
                   "## 3. Regressions (diff scopes)\n%s\n\n## 4. Prioritized findings\n- AUD-9 elsewhere\n"
                   % (commit[:7], regressions))

    def setUp(self):
        super().setUp()
        self.git("switch", "-q", "-c", "fix/android-x")
        self.write("android/app/src/main/A.kt", "class A { val fixed = true }\n")
        self.head = self.commit("fix")

    def test_current_report_without_regressions_passes(self):
        self.report(self.head)
        self.assertTrue(self.gate(self.state(), "G5 audit").startswith("Passed"))

    def test_open_regression_blocks(self):
        self.report(self.head, "- AUD-1 · timer submits twice")
        lines = self.state()
        self.assertTrue(any("regressions=AUD-1 " in l for l in lines), "\n".join(lines))
        self.assertEqual(self.gate(lines, "G5 audit"), "Blocked (open regressions in a current audit report)")

    def test_reviewable_change_after_report_makes_it_stale(self):
        self.report(self.head)
        self.write("android/app/src/main/A.kt", "class A { val more = true }\n")
        self.commit("more")
        self.assertEqual(self.gate(self.state(), "G5 audit"), "Unverified (no current audit report)")


class CheckStagedTest(RepoCase):
    def check(self, expected, *includes):
        path = os.path.join(self.tmp, "expected.txt")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("".join(p + "\0" for p in expected))
        args = [sys.executable, "-I", self.pr_state, "check-staged", "--repo", self.repo, "--expect", path]
        for include in includes:
            args += ["--include", include]
        return subprocess.run(args, capture_output=True, text=True)

    def test_exact_match_passes(self):
        self.write("a.md", "a\n")
        self.git("add", "a.md")
        proc = self.check(["a.md"], "a.md")
        self.assertEqual(proc.returncode, 0, proc.stdout)
        self.assertIn("staged check: Passed", proc.stdout)

    def test_extra_missing_and_forbidden_fail(self):
        self.write("a.md", "a\n")
        self.write("goldie/x.json", "x\n")
        self.git("add", "a.md", "goldie/x.json")
        proc = self.check(["a.md", "b.md"], "a.md")
        self.assertEqual(proc.returncode, 1)
        self.assertIn("unexpected: goldie/x.json", proc.stdout)
        self.assertIn("missing: b.md", proc.stdout)
        self.assertIn("forbidden: goldie/x.json", proc.stdout)

    def test_a_tampered_list_cannot_vouch_for_itself(self):
        # SEC-1: the list and the staging area agree, but the file is not a PR file.
        self.write("a.md", "a\n")
        self.write("secrets/key.txt", "k\n")
        self.git("add", "a.md", "secrets/key.txt")
        proc = self.check(["a.md", "secrets/key.txt"], "a.md")
        self.assertEqual(proc.returncode, 1)
        self.assertIn("not a PR file: secrets/key.txt", proc.stdout)


class HardeningTest(RepoCase):
    """Regression tests for the 2026-11-06 security review (SEC-n)."""

    def setUp(self):
        super().setUp()
        self.git("switch", "-q", "-c", "chore/x")

    def files_out(self, *includes):
        out = os.path.join(self.tmp, "lists", "files-%d.txt" % len(os.listdir(self.tmp)))
        args = []
        for include in includes:
            args += ["--include", include]
        lines = self.state(*(args + ["--files-out", out]))
        content = None
        if os.path.exists(out):
            with open(out, encoding="utf-8") as handle:
                content = [p for p in handle.read().split("\0") if p]
        return lines, content

    def test_sec1_newline_in_a_name_never_reaches_the_staging_list(self):
        self.write("android/feature/x/ok.kt", "ok\n")
        self.write("secrets/key.txt", "k\n")
        self.write("android/feature/x/a\nsecrets/key.txt\nandroid/feature/x/b", "x\n")
        lines, content = self.files_out("android/feature/x")
        self.assertTrue(any(l.startswith("file invalid-name ") and "\\n" in l for l in lines), "\n".join(lines))
        self.assertTrue(self.gate(lines, "G2b file names").startswith("Blocked"))
        self.assertIsNone(content)
        self.assertTrue(any(l.startswith("files-out: not written (G2b file names Blocked)") for l in lines))

    def test_sec1_staging_list_is_nul_separated(self):
        self.write("docs/a b.md", "x\n")
        self.write("docs/c.md", "x\n")
        _, content = self.files_out("docs")
        self.assertEqual(sorted(content), ["docs/a b.md", "docs/c.md"])

    def test_sec4_foreign_repository_is_refused(self):
        proc = subprocess.run([sys.executable, "-I", REAL_PR_STATE, "state", "--repo", self.repo],
                              capture_output=True, text=True)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("this script only acts on", proc.stderr)

    def test_sec5_fsmonitor_and_external_diff_never_run(self):
        tools = tempfile.mkdtemp(prefix="android-pr-tools-")
        self.addCleanup(shutil.rmtree, tools)
        fsmonitor, fsmonitor_ran = marker_script(tools, "fsmonitor")
        external, external_ran = marker_script(tools, "external")
        self.write("android/app/src/main/A.kt", "class A2\n")
        self.git("config", "core.fsmonitor", fsmonitor)
        self.git("config", "diff.external", external)
        self.state("--include", "android")
        self.assertFalse(os.path.exists(fsmonitor_ran))
        self.assertFalse(os.path.exists(external_ran))

    def test_sec7_worktree_rename_is_parsed(self):
        self.write("docs/old.md", "same content\n")
        self.commit("old", "docs/old.md")
        os.rename(os.path.join(self.repo, "docs/old.md"), os.path.join(self.repo, "docs/new.md"))
        self.git("add", "-N", "docs/new.md")
        lines = self.state("--include", "docs")
        # Working-tree entries only: old.md is also listed as committed on this branch.
        listed = sorted(l.split(" ", 3)[3] for l in lines if l.startswith("file ") and not l.startswith("file committed "))
        self.assertEqual(listed, ["docs/new.md", "docs/old.md"])

    def test_sec8_upper_case_secret_extension(self):
        self.write("android/app/Upload.JKS", "binary\n")
        lines = self.state("--include", "android")
        self.assertIn("android/app/Upload.JKS", self.files(lines, "secret-risk"))

    def test_sec8_tokens_and_api_keys_are_detected(self):
        github = "gh" + "o_" + "A" * 36
        google = "AI" + "za" + "B" * 35
        self.write("tools/gh.yml", "github.com:\n    oauth" + "_token: " + github + "\n")
        self.write("tools/config.json", '{"key": "%s"}\n' % google)
        secrets = self.line(self.state("--include", "tools"), "secrets: ")
        self.assertIn("tools/gh.yml: GitHub token", secrets)
        self.assertIn("tools/gh.yml: oauth_token field", secrets)
        self.assertIn("tools/config.json: Google API key", secrets)
        self.assertNotIn(github, secrets)
        self.assertNotIn(google, secrets)

    def test_sec8_marker_at_the_end_of_a_large_file(self):
        self.write("tools/big.txt", "a" * (2 * 1024 * 1024) + "\n" + PRIVATE_KEY_LINE)
        self.assertIn("tools/big.txt: private key block", self.line(self.state("--include", "tools"), "secrets: "))

    def test_sec8_no_staging_list_when_secrets_block(self):
        self.write("tools/key.txt", PRIVATE_KEY_LINE)
        lines, content = self.files_out("tools")
        self.assertIsNone(content)
        self.assertTrue(any(l.startswith("files-out: not written (G3 secrets Blocked)") for l in lines))

    def test_sec9_local_settings_are_never_staged(self):
        self.write(".claude/settings.local.json", "{}\n")
        self.write(".claude/skills/android-pr/__pycache__/x.cpython-39.pyc", "x")
        lines = self.state("--include", ".claude")
        self.assertIn(".claude/settings.local.json", self.files(lines, "never-stage"))
        self.assertIn(".claude/skills/android-pr/__pycache__/x.cpython-39.pyc", self.files(lines, "never-stage"))


class ReadOnlyTest(RepoCase):
    def test_state_does_not_change_index_or_tree(self):
        self.git("switch", "-q", "-c", "chore/x")
        self.write("notes.md", "x\n")
        before = (self.git("status", "--porcelain=v1", "--untracked-files=all"), self.git("diff", "--cached"))
        self.state("--include", "notes.md")
        after = (self.git("status", "--porcelain=v1", "--untracked-files=all"), self.git("diff", "--cached"))
        self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main(verbosity=2)
