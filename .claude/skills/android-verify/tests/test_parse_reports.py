"""Tests for parse_reports.py and the refusal paths of run_gradle.sh.

Run from anywhere with:  python3 -I .claude/skills/android-verify/tests/test_parse_reports.py
No test starts Gradle, adb or a device. Fixtures in fixtures/ are derived from
the project's real reports (JUnit XML, lint XML, console lines from the
2026-11-06 CI run); junit-fail.xml, lint-error.xml and the failure consoles
are synthetic.
"""

import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SKILL = os.path.dirname(HERE)
REPO = os.path.dirname(os.path.dirname(os.path.dirname(SKILL)))
PARSER = os.path.join(SKILL, "parse_reports.py")
RUNNER = os.path.join(SKILL, "run_gradle.sh")
FIXTURES = os.path.join(HERE, "fixtures")
TOOLS = os.path.join(REPO, ".claude", "tools")
F04_BASELINE = os.path.join(REPO, "docs", "android-audit", "evidence", "F-04", "green-ci-reports-summary.txt")


def install_verify(root):
    """Copy android-verify (with its fixtures) and .claude/tools into root.

    The scripts act only on the repository that contains them, so each test
    runs a copy installed in its own temporary repository."""
    skill = os.path.join(root, ".claude", "skills", "android-verify")
    shutil.copytree(SKILL, skill, ignore=shutil.ignore_patterns("__pycache__", "test_*.py"))
    shutil.copytree(TOOLS, os.path.join(root, ".claude", "tools"), ignore=shutil.ignore_patterns("__pycache__", "tests"))
    return os.path.join(skill, "parse_reports.py"), os.path.join(skill, "tests", "fixtures")

UP_TO_DATE = "Passed (up to date, not executed in this run)"


class ParserCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="android-verify-test-")
        self.root = os.path.join(self.tmp, "repo")
        self.results = os.path.join(self.root, "results")
        os.makedirs(self.results)
        self.parser, self.fixtures = install_verify(self.root)
        self.since = time.time()

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def junit(self, name, fresh):
        target = os.path.join(self.results, "TEST-" + name)
        shutil.copy(os.path.join(self.fixtures, name), target)
        self.touch(target, fresh)

    def lint(self, name, fresh):
        with open(os.path.join(self.fixtures, name), encoding="utf-8") as handle:
            text = handle.read().replace("{REPO}", self.root)
        target = os.path.join(self.root, "lint.xml")
        with open(target, "w", encoding="utf-8") as handle:
            handle.write(text)
        self.touch(target, fresh)
        return target

    def touch(self, path, fresh):
        stamp = self.since + 5 if fresh else self.since - 3600
        os.utime(path, (stamp, stamp))

    def run_parser(self, *args, expect_ok=True):
        proc = subprocess.run([sys.executable, "-I", self.parser] + list(args),
                              capture_output=True, text=True)
        if expect_ok:
            self.assertEqual(proc.returncode, 0, proc.stderr)
        return proc

    def summarize(self, console=None, exit_code="0", lint=None, extra=()):
        args = ["summarize", "--repo", self.root, "--junit", self.results,
                "--since", str(self.since)]
        if console:
            args += ["--console", os.path.join(self.fixtures, console), "--exit-code", exit_code]
        if lint:
            args += ["--lint", lint]
        args += list(extra)
        return self.run_parser(*args).stdout

    def check(self, output, name):
        prefix = "check %s: " % name
        lines = [line[len(prefix):] for line in output.splitlines() if line.startswith(prefix)]
        self.assertEqual(len(lines), 1, output)
        return lines[0]


class FreshnessTest(ParserCase):
    def test_executed_with_fresh_reports_is_passed(self):
        self.junit("junit-pass.xml", fresh=True)
        out = self.summarize("console-ci-executed.txt", lint=self.lint("lint-warnings.xml", fresh=True))
        self.assertIn("unit tests: 2 tests in 1 classes, 0 failed, 0 skipped", out)
        self.assertEqual(self.check(out, "build"), "Passed")
        self.assertEqual(self.check(out, "unit tests"), "Passed")
        self.assertEqual(self.check(out, "lint"), "Passed")

    def test_up_to_date_tests_with_fresh_lint_are_judged_separately(self):
        # The 2026-11-06 CI run: testDebugUnitTest UP-TO-DATE, lintDebug executed.
        self.junit("junit-pass.xml", fresh=False)
        out = self.summarize("console-ci-uptodate.txt", lint=self.lint("lint-warnings.xml", fresh=True))
        self.assertIn("unit tests: 2 tests in 1 classes", out)
        self.assertIn("task: :app:testDebugUnitTest UP-TO-DATE", out)
        self.assertNotIn("stale result files ignored", out)
        self.assertEqual(self.check(out, "unit tests"), UP_TO_DATE)
        self.assertEqual(self.check(out, "lint"), "Passed")

    def test_lint_freshness_follows_the_report_task_not_lintDebug(self):
        # The second 2026-11-06 CI run: lintDebug executed, but lintReportDebug,
        # which writes lint-results-debug.xml, was UP-TO-DATE.
        self.junit("junit-pass.xml", fresh=False)
        out = self.summarize("console-ci-noop.txt", lint=self.lint("lint-warnings.xml", fresh=False))
        self.assertEqual(self.check(out, "unit tests"), UP_TO_DATE)
        self.assertEqual(self.check(out, "lint"), UP_TO_DATE)
        self.assertIn("lint severities: {'Warning': 2}", out)

    def test_up_to_date_lint_is_not_reported_as_executed(self):
        self.junit("junit-pass.xml", fresh=True)
        out = self.summarize("console-lint-from-cache.txt", lint=self.lint("lint-warnings.xml", fresh=False))
        self.assertEqual(self.check(out, "unit tests"), "Passed")
        self.assertEqual(self.check(out, "lint"), UP_TO_DATE)

    def test_executed_task_with_only_stale_results_is_not_run(self):
        self.junit("junit-pass.xml", fresh=False)
        out = self.summarize("console-ci-executed.txt", lint=self.lint("lint-warnings.xml", fresh=False))
        self.assertIn("stale result files ignored: 1", out)
        self.assertEqual(self.check(out, "unit tests"), "Not run (no fresh results)")
        self.assertEqual(self.check(out, "lint"), "Not run (no fresh report)")

    def test_explicit_since_overrides_per_report(self):
        self.junit("junit-pass.xml", fresh=False)
        lint = self.lint("lint-warnings.xml", fresh=False)
        out = self.summarize("console-ci-executed.txt", lint=lint,
                             extra=["--junit-since", "0", "--lint-since", str(self.since + 60)])
        self.assertEqual(self.check(out, "unit tests"), "Passed")
        self.assertEqual(self.check(out, "lint"), "Not run (no fresh report)")

    def test_without_console_or_since_freshness_needs_verification(self):
        self.junit("junit-pass.xml", fresh=False)
        out = self.run_parser("summarize", "--repo", self.root, "--junit", self.results).stdout
        self.assertEqual(self.check(out, "unit tests"),
                         "Needs verification (no console log and no since time)")


class FailureTest(ParserCase):
    def test_failed_test_task_reports_failure_and_target(self):
        self.junit("junit-fail.xml", fresh=True)
        raw = os.path.join(self.tmp, "raw.txt")
        out = self.summarize("console-test-failed.txt", exit_code="1",
                             extra=["--target-test", "answerTapped*", "--raw-out", raw])
        self.assertIn("unit tests: 2 tests in 1 classes, 1 failed, 0 skipped", out)
        self.assertIn("failed: answerTappedAtDeadlineWithPendingSave_isCountedInResult", out)
        self.assertIn("target answerTappedAtDeadlineWithPendingSave_isCountedInResult: FAIL", out)
        self.assertTrue(self.check(out, "build").startswith("Failed (BUILD FAILED, exit 1)"))
        self.assertEqual(self.check(out, "unit tests"), "Failed (1 failed)")
        with open(raw, encoding="utf-8") as handle:
            listing = handle.read()
        self.assertIn("expected:<1> but was:<0>", listing)
        self.assertIn("MockExamViewModelTest.kt:147", listing)

    def test_compile_failure_means_tests_not_run(self):
        self.junit("junit-pass.xml", fresh=False)
        out = self.summarize("console-compile-failed.txt", exit_code="1",
                             lint=self.lint("lint-warnings.xml", fresh=False))
        self.assertEqual(self.check(out, "unit tests"), "Not run (:app:testDebugUnitTest not in console)")
        self.assertEqual(self.check(out, "lint"), "Not run (:app:lintReportDebug not in console)")

    def test_lint_errors_fail_the_lint_check(self):
        self.junit("junit-pass.xml", fresh=True)
        out = self.summarize("console-ci-executed.txt", lint=self.lint("lint-error.xml", fresh=True))
        self.assertEqual(self.check(out, "lint"), "Failed (1 errors)")

    def test_results_from_another_device_fail(self):
        self.junit("connected.xml", fresh=True)
        out = self.run_parser("summarize", "--repo", self.root, "--junit", self.results,
                              "--since", str(self.since), "--expect-device", "emulator-5556").stdout
        self.assertIn("DEVICE MISMATCH", out)
        self.assertEqual(self.check(out, "instrumented tests"), "Failed (results from another device)")

    def test_lint_in_changed_files_uses_repo_relative_paths(self):
        self.junit("junit-pass.xml", fresh=True)
        changed = os.path.join(self.tmp, "changed.txt")
        with open(changed, "w", encoding="utf-8") as handle:
            handle.write("android/app/src/main/java/com/nextdaymobilestudio/lebenindeutschland/"
                         "feature/mockexam/MockExamViewModel.kt\n")
        out = self.summarize("console-ci-executed.txt", lint=self.lint("lint-warnings.xml", fresh=True),
                             extra=["--changed", changed])
        self.assertIn("lint in changed files: EmptySuperCall android/app/src/main/java/", out)
        self.assertIn("MockExamViewModel.kt:247", out)
        self.assertNotIn(self.root, out)


FRESH_TASKS = [":app:testDebugUnitTest", ":app:lintAnalyzeDebug", ":app:lintAnalyzeDebugUnitTest",
               ":app:lintAnalyzeDebugAndroidTest", ":app:lintReportDebug"]


class FreshModeTest(ParserCase):
    def fresh(self, console, exit_code="0", lint_fresh=True):
        extra = []
        for task in FRESH_TASKS:
            extra += ["--require-executed", task]
        return self.summarize(console, exit_code=exit_code,
                              lint=self.lint("lint-warnings.xml", fresh=lint_fresh), extra=extra)

    def test_all_forced_tasks_executed_is_fresh(self):
        self.junit("junit-pass.xml", fresh=True)
        out = self.fresh("console-ci-fresh.txt")
        self.assertEqual(self.check(out, "build"), "Passed")
        self.assertEqual(self.check(out, "unit tests"), "Passed")
        self.assertEqual(self.check(out, "lint"), "Passed")
        self.assertEqual(self.check(out, "fresh"), "Passed (5 forced tasks executed)")

    def test_reused_lint_analysis_fails_the_fresh_check(self):
        # lintReportDebug executed, so the lint check alone would pass; the
        # reused analysis must still fail fresh mode.
        self.junit("junit-pass.xml", fresh=True)
        out = self.fresh("console-ci-fresh-lint-reused.txt")
        self.assertEqual(self.check(out, "lint"), "Passed")
        self.assertEqual(self.check(out, "fresh"),
                         "Failed (not executed: :app:lintAnalyzeDebug UP-TO-DATE)")

    def test_failed_test_step_fails_build_but_lint_step_is_still_judged(self):
        self.junit("junit-fail.xml", fresh=True)
        out = self.fresh("console-ci-fresh-tests-failed.txt", exit_code="1")
        self.assertIn("build: BUILD FAILED (1 of 2 invocations failed), exit 1", out)
        self.assertTrue(self.check(out, "build").startswith("Failed"))
        self.assertEqual(self.check(out, "unit tests"), "Failed (1 failed)")
        self.assertEqual(self.check(out, "lint"), "Passed")
        self.assertEqual(self.check(out, "fresh"), "Passed (5 forced tasks executed)")

    def test_task_executed_in_one_step_is_not_hidden_by_a_later_up_to_date(self):
        console = os.path.join(self.tmp, "console.txt")
        with open(console, "w", encoding="utf-8") as handle:
            handle.write("> Task :app:lintReportDebug\nBUILD SUCCESSFUL\n"
                         "> Task :app:lintReportDebug UP-TO-DATE\nBUILD SUCCESSFUL\n")
        out = self.run_parser("summarize", "--repo", self.root, "--console", console, "--exit-code", "0",
                              "--require-executed", ":app:lintReportDebug").stdout
        self.assertEqual(self.check(out, "fresh"), "Passed (1 forced tasks executed)")

    def test_plain_ci_output_has_no_fresh_check(self):
        self.junit("junit-pass.xml", fresh=False)
        out = self.summarize("console-ci-noop.txt", lint=self.lint("lint-warnings.xml", fresh=False))
        self.assertNotIn("check fresh", out)


class CompareAndSanitizeTest(ParserCase):
    def write(self, name, text):
        path = os.path.join(self.tmp, name)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        return path

    @unittest.skipUnless(os.path.isfile(F04_BASELINE), "F-04 evidence not present")
    def test_compare_reads_the_legacy_f04_summary(self):
        current = self.write("current.txt",
                             "unit tests: 86 tests in 17 classes, 0 failed, 0 skipped\n"
                             "lint severities: {'Warning': 57}\n"
                             "lint ids: {'PluralsCandidate': 27, 'NewerVersionAvailable': 8, 'UnusedResources': 7, "
                             "'GradleDependency': 6, 'ModifierParameter': 3, 'AndroidGradlePluginVersion': 2, "
                             "'MonochromeLauncherIcon': 2, 'EmptySuperCall': 1, 'RedundantLabel': 1}\n")
        baseline = os.path.join(self.root, "docs", "android-audit", "evidence", "F-04", "green-ci-reports-summary.txt")
        os.makedirs(os.path.dirname(baseline))
        shutil.copy(F04_BASELINE, baseline)
        out = self.run_parser("compare", "--baseline", baseline, "--current", current).stdout
        self.assertIn("tests: 86 -> 86 (+0); failed 0 -> 0", out)
        self.assertIn("lint id changes: NewerVersionAvailable +1", out)

    def test_compare_reports_check_status_changes(self):
        before = self.write("before.txt", "check unit tests: %s\ncheck lint: Passed\n" % UP_TO_DATE)
        after = self.write("after.txt", "check unit tests: Passed\ncheck lint: Passed\n")
        out = self.run_parser("compare", "--baseline", before, "--current", after).stdout
        self.assertIn("check unit tests: %s -> Passed" % UP_TO_DATE, out)
        self.assertIn("check lint: Passed (unchanged)", out)

    def test_sanitize_scrubs_paths_and_refuses_unsafe_targets(self):
        source = self.write("x-raw.txt", "at %s/android/app/Foo.kt:1\n" % self.root)
        evidence = os.path.join(self.root, "docs", "android-audit", "evidence", "F-99", "raw.txt")
        self.run_parser("sanitize", "--repo", self.root, source, evidence)
        with open(evidence, encoding="utf-8") as handle:
            self.assertEqual(handle.read(), "at <repo>/android/app/Foo.kt:1\n")
        again = self.run_parser("sanitize", "--repo", self.root, source, evidence, expect_ok=False)
        self.assertIn("refusing to overwrite", again.stderr)
        inside = self.run_parser("sanitize", "--repo", self.root, source,
                                 os.path.join(self.root, "android", "x.txt"), expect_ok=False)
        self.assertIn("inside the repository, only docs/android-audit/evidence/ allowed", inside.stderr)

    def test_writes_outside_temporary_directories_are_refused(self):
        # Never created: every case is refused before anything is written.
        home_target = os.path.join(os.path.expanduser("~"), "android-verify-must-not-exist.txt")
        source = self.write("x-raw.txt", "x\n")
        self.junit("junit-pass.xml", fresh=True)
        cases = [
            ["sanitize", "--repo", self.root, source, home_target],
            ["summarize", "--repo", self.root, "--junit", self.results, "--out", home_target],
            ["summarize", "--repo", self.root, "--junit", self.results, "--raw-out", home_target],
            ["summarize", "--repo", self.root, "--junit", self.results,
             "--raw-out", os.path.join(self.root, "docs", "android-audit", "evidence", "raw.txt")],
        ]
        for args in cases:
            proc = self.run_parser(*args, expect_ok=False)
            self.assertNotEqual(proc.returncode, 0, args)
            self.assertIn("refusing to write", proc.stderr)
        self.assertFalse(os.path.lexists(home_target))

    def test_sanitize_copies_only_raw_listings_from_temporary_directories(self):
        # SEC-2: sanitize must not carry an arbitrary file into the evidence folder.
        evidence = os.path.join(self.root, "docs", "android-audit", "evidence", "F-99", "x-raw.txt")
        in_repo = os.path.join(self.root, "notes-raw.txt")
        with open(in_repo, "w", encoding="utf-8") as handle:
            handle.write("x\n")
        # Assembled at runtime so this file does not contain a secret marker itself.
        for source in (self.write("hosts.yml", "oauth" + "_token: x\n"), in_repo,
                       os.path.expanduser("~/.config/gh/hosts.yml")):
            proc = self.run_parser("sanitize", "--repo", self.root, source, evidence, expect_ok=False)
            self.assertNotEqual(proc.returncode, 0, source)
            self.assertIn("only a *-raw.txt listing from a temporary directory", proc.stderr)
        self.assertFalse(os.path.lexists(evidence))

    def test_inputs_outside_the_repository_and_temp_are_refused(self):
        outside = os.path.expanduser("~/.zshrc")
        for args in (["summarize", "--repo", self.root, "--console", outside],
                     ["summarize", "--repo", self.root, "--meta", outside],
                     ["compare", "--baseline", outside, "--current", outside]):
            proc = self.run_parser(*args, expect_ok=False)
            self.assertNotEqual(proc.returncode, 0, args)
            self.assertIn("refusing to read", proc.stderr)

    def test_foreign_repository_is_refused(self):
        # SEC-4: the real parser may act only on the real repository.
        proc = subprocess.run([sys.executable, "-I", PARSER, "summarize", "--repo", self.root],
                              capture_output=True, text=True)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("this script only acts on", proc.stderr)


class RunnerRefusalTest(unittest.TestCase):
    """Every case here is refused before Gradle or adb starts."""

    def setUp(self):
        self.log_dir = tempfile.mkdtemp(prefix="android-verify-log-")

    def tearDown(self):
        shutil.rmtree(self.log_dir)

    def refused(self, *args):
        proc = subprocess.run([RUNNER] + list(args), capture_output=True, text=True)
        self.assertEqual(proc.returncode, 2, proc.stdout + proc.stderr)
        self.assertNotIn("since=", proc.stdout)
        self.assertEqual(os.listdir(self.log_dir), [])
        return proc.stderr

    def test_unknown_tier(self):
        self.assertIn("usage", self.refused("clean", self.log_dir))

    def test_log_dir_inside_repository(self):
        self.assertIn("inside the repository", self.refused("ci", SKILL))

    def test_ci_takes_no_options(self):
        self.assertIn("ci takes only --fresh", self.refused("ci", self.log_dir, "--rerun"))

    def test_focused_rejects_shell_characters(self):
        self.assertIn("--tests may contain only", self.refused("focused", self.log_dir, "--tests", "*Mock*;rm"))

    def test_focused_requires_tests(self):
        self.assertIn("requires --tests", self.refused("focused", self.log_dir))

    def test_instrumented_refuses_protected_and_non_emulator_serials(self):
        self.assertIn("is protected", self.refused("instrumented", self.log_dir, "--device", "emulator-5554"))
        self.assertIn("must be an emulator", self.refused("instrumented", self.log_dir, "--device", "R5CT1234"))

    def test_unknown_option(self):
        self.assertIn("usage", self.refused("ci", self.log_dir, "--offline"))

    def test_log_dir_outside_temporary_directories(self):
        proc = subprocess.run([RUNNER, "ci", os.path.expanduser("~"), "--dry-run"], capture_output=True, text=True)
        self.assertEqual(proc.returncode, 2)
        self.assertIn("log dir must be inside a temporary directory", proc.stderr)

    def test_tmpdir_prefix_cannot_widen_the_log_dir_rule(self):
        env = dict(os.environ, TMPDIR=os.path.expanduser("~"))
        proc = subprocess.run([RUNNER, "ci", os.path.expanduser("~"), "--dry-run"], capture_output=True,
                              text=True, env=env)
        self.assertEqual(proc.returncode, 2)
        self.assertIn("log dir must be inside a temporary directory", proc.stderr)

    def test_shared_tmp_root_is_refused(self):
        # SEC-6: run names are predictable, so /tmp itself is not a log dir.
        proc = subprocess.run([RUNNER, "ci", "/tmp", "--dry-run"], capture_output=True, text=True)
        self.assertEqual(proc.returncode, 2)
        self.assertIn("must be a private subfolder", proc.stderr)

    def test_log_dir_writable_by_others_is_refused(self):
        os.chmod(self.log_dir, 0o777)
        self.assertIn("must not be writable by other users", self.refused("ci", self.log_dir))

    def test_fresh_is_only_for_ci(self):
        self.assertIn("--fresh is only for ci", self.refused("focused", self.log_dir, "--tests", "X", "--fresh"))
        self.assertIn("--fresh is only for ci", self.refused("release", self.log_dir, "--fresh"))

    def test_ci_fresh_takes_no_other_options(self):
        self.assertIn("ci takes only --fresh", self.refused("ci", self.log_dir, "--fresh", "--rerun"))
        self.assertIn("ci takes only --fresh", self.refused("ci", self.log_dir, "--fresh", "--tests", "X"))


class RunnerDryRunTest(unittest.TestCase):
    """--dry-run prints the exact Gradle commands and writes nothing."""

    def setUp(self):
        self.log_dir = tempfile.mkdtemp(prefix="android-verify-log-")

    def tearDown(self):
        shutil.rmtree(self.log_dir)

    def dry_run(self, *args):
        proc = subprocess.run([RUNNER] + list(args) + ["--dry-run"], capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(os.listdir(self.log_dir), [])
        self.assertNotIn("since=", proc.stdout)
        return proc.stdout.splitlines()

    def test_plain_ci_is_unchanged(self):
        self.assertEqual(self.dry_run("ci", self.log_dir), [
            "dry-run=1",
            "command=android/gradlew -p android assembleDebug lintDebug testDebugUnitTest --stacktrace",
        ])

    def test_ci_fresh_forces_only_tests_and_lint_tasks(self):
        lines = self.dry_run("ci", self.log_dir, "--fresh")
        self.assertEqual(lines, [
            "dry-run=1",
            "command.tests=android/gradlew -p android assembleDebug :app:testDebugUnitTest --rerun --stacktrace",
            "command.lint=android/gradlew -p android lintDebug :app:lintAnalyzeDebug --rerun "
            ":app:lintAnalyzeDebugUnitTest --rerun :app:lintAnalyzeDebugAndroidTest --rerun "
            ":app:lintReportDebug --rerun --stacktrace",
        ])
        self.assertFalse(any("--rerun-tasks" in line for line in lines))

    def test_focused_rerun(self):
        self.assertEqual(self.dry_run("focused", self.log_dir, "--tests", "*MockExam*", "--rerun"), [
            "dry-run=1",
            "command=android/gradlew -p android :app:testDebugUnitTest --tests *MockExam* --rerun --stacktrace",
        ])


if __name__ == "__main__":
    unittest.main(verbosity=2)
