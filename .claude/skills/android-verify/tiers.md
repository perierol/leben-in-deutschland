# Tiers: commands, safety checks and artefacts

Run every command from the repository root, one plain command per Bash call. `<S>` is the session scratchpad directory, `<run>` is the run slug (`YYYYMMDD-HHMMSS-<tier>`), printed by the runner.

Gradle runs only through `.claude/skills/android-verify/run_gradle.sh <tier> <S> [options]`. The script is the allowlist:
- it maps each tier to one fixed `android/gradlew -p android … --stacktrace` command and validates every option;
- it accepts a log directory only if it is a private subfolder of a temporary directory (not `/tmp` itself, owned by you, not writable by others, outside the repository). `$TMPDIR` is ignored;
- it creates the log, meta and changed-file files with `noclobber`, so an existing file or planted symlink is never written through;
- it drops `GIT_*`, `ORG_GRADLE_PROJECT_*`, `GRADLE_OPTS` and the JVM option variables before running git or Gradle;
- it records `since` just before Gradle starts, and writes the console to `<S>/<run>.log`.

A permission rule can then match one plain command.

## Scope grammar

```
/android-verify [tier] [--fresh] [--tests <pattern>] [--rerun] [--finding F-xx --phase red|green|review]
                [--target <test name pattern>] [--device <serial>] [--baseline <file>] [--plan]

tier := ci            # default
      | focused       # requires --tests
      | instrumented  # requires --device
      | release
      | content
      | remote [<pr number>]
```

- `--finding` requires `--phase`, and `--phase` requires `--finding`. `F-xx` must exist as a heading in `docs/android-audit/findings.md`.
- `--target` names the test (or a glob of test names) whose RED/GREEN state the phase check judges. Default for `focused`: none, so every failure counts.
- `--phase red` is allowed only with `focused` (or `instrumented`), because a RED run is expected to fail.
- `--fresh` is allowed only with `ci`, and takes no other tier option. See [`ci --fresh`](#ci---fresh).
- `--rerun` is allowed only with `focused`. It forces a fresh execution without a finding or phase, and writes evidence only to the scratchpad. `--phase` implies it.
- Any unknown token is an error: show this grammar and stop.

## Allowlist

The tasks below are the only Gradle invocations. Never add `clean`, `lintFix`, `updateDaemonJvm`, `--write-locks`, `--refresh-dependencies`, `--offline` changes, `-P` properties, init scripts or any other task.

| Tier | You run | Gradle command the script runs | Pre-approved? |
|---|---|---|---|
| `ci` | `run_gradle.sh ci <S>` | `assembleDebug lintDebug testDebugUnitTest --stacktrace` | yes |
| `ci --fresh` | `run_gradle.sh ci <S> --fresh` | two invocations: `assembleDebug :app:testDebugUnitTest --rerun --stacktrace`, then `lintDebug :app:lintAnalyzeDebug --rerun :app:lintAnalyzeDebugUnitTest --rerun :app:lintAnalyzeDebugAndroidTest --rerun :app:lintReportDebug --rerun --stacktrace` | yes |
| `focused` | `run_gradle.sh focused <S> --tests '<pattern>'` | `:app:testDebugUnitTest --tests '<pattern>' --stacktrace` | yes |
| `focused` + `--rerun` or `--phase` | the same, plus `--rerun` | the same, with `--rerun` (forces the test task to execute) | yes |
| `instrumented` | `run_gradle.sh instrumented <S> --device <serial>` | `connectedDebugAndroidTest --stacktrace`, with `ANDROID_SERIAL=<serial>` | **no**: the permission prompt is a deliberate second confirmation |
| `release` | `run_gradle.sh release <S>`, then `android analyze android/app/build/outputs/apk/release/app-release-unsigned.apk` | `assembleRelease --stacktrace` | yes |
| `content` | `python3 -I -m unittest discover -s scripts/content/tests -v` | yes (exact command) |
| `remote` | `gh pr checks <n>`, `gh pr view <n> --json …`, `gh run list …`, `gh run view <id>` | yes |

`--tests` patterns: letters, digits, `.`, `*`, `_` and `$` only, always single-quoted. The script rejects anything else.

`--dry-run` (any tier) validates the options and prints the exact `command…=` lines, without running Gradle, adb or git and without writing anything. `--plan` uses it.

`--rerun` is Gradle's built-in per-task option: it forces only the task written before it. `--rerun-tasks`, which forces every task in the build, is never used.

Pre-approved rules name each tier separately (`run_gradle.sh ci *`, `run_gradle.sh focused *`, `run_gradle.sh release *`). There is deliberately no rule for `run_gradle.sh instrumented`, for `android/gradlew` or for `bash`.

Other commands are pre-approved only as exact strings, without wildcards: `adb devices -l`, the `android analyze` command above and the `content` command. `adb -s <serial> emu avd name` is **not** pre-approved, because a wildcard in the middle of a rule would also match `adb -s <serial> shell … emu avd name`; it prompts as part of the instrumented checks. Read-only git goes through `.claude/tools/git_read.py`.

## `ci` (default)

Same command as `.github/workflows/android-ci.yml`. CI runs on JDK 25 (Temurin), and `android/gradle/gradle-daemon-jvm.properties` asks for toolchain 25.

| Artefact | Path |
|---|---|
| Unit test results | `android/app/build/test-results/testDebugUnitTest/TEST-*.xml` |
| Lint | `android/app/build/reports/lint-results-debug.xml` |
| Debug APK | `android/app/build/outputs/apk/debug/app-debug.apk` |

Does not prove: instrumented tests, release/R8 behaviour, device behaviour, TalkBack, Firestore rules.

**UP-TO-DATE.** The parser judges unit tests by `:app:testDebugUnitTest` and lint by `:app:lintReportDebug`, separately. (`:app:lintReportDebug` writes `lint-results-debug.xml`. `:app:lintDebug` shows as executed on every run even when the report is reused, so it says nothing about freshness.) When a task is `UP-TO-DATE` or `FROM-CACHE`, its reports come from an earlier execution with the same inputs, and the parser reports *Passed (up to date, not executed in this run)*. When a task executed, only reports written after `since` count. One run can therefore have up-to-date tests and freshly executed lint. For phase evidence, use `focused` with `--rerun` instead.

## `ci --fresh`

Use this when the plain `ci` run would reuse up-to-date test or lint results. Two invocations write to the same log, marked with `# run_gradle step tests:` and `# run_gradle step lint:` lines:

| Step | Tasks | Forced with `--rerun` |
|---|---|---|
| `tests` | `assembleDebug :app:testDebugUnitTest` | `:app:testDebugUnitTest` (all unit tests) |
| `lint` | `lintDebug` plus its four tasks | `:app:lintAnalyzeDebug`, `:app:lintAnalyzeDebugUnitTest`, `:app:lintAnalyzeDebugAndroidTest`, `:app:lintReportDebug` |

- Compilation and `assembleDebug` are **not** forced. They stay incremental, so the run checks fresh tests and lint against the current build, not a clean build.
- The task set matches plain `ci`: the same three entry tasks, plus the lint tasks they already depend on.
- The lint step runs even if the tests step fails, so one run reports both. The script prints `exit.tests=`, `exit.lint=`, and `exit=`, which is the first non-zero exit code (or 0).
- **Parse once**, with the overall `exit` and five `--require-executed` flags, one for each forced task. That adds a `check fresh:` line, which is **Failed** if any forced task was `UP-TO-DATE`, `FROM-CACHE` or missing. A run with a failed fresh check is not fresh evidence, even if every other check passed.
- The baseline comparison works as for plain `ci`.

## `focused`

For RED/GREEN runs on one class or pattern. Parse with `--target` when a phase is set. The suite totals cover only the selected tests, so never compare them with a full-suite baseline: skip the baseline step and say so.

## `instrumented`

Runs the 21 files under `android/app/src/androidTest/`. **`connectedDebugAndroidTest` installs a test APK and uninstalls the app afterwards, which wipes its data on that device.**

Protected devices, never used and never allowed to be online during the run:

| Protected | Why |
|---|---|
| `emulator-5554` | personal emulator |
| AVD `Pixel_9a` | personal emulator; holds study progress |
| AVD `Pixel_9_Pro_Goldie` | holds Goldie store-asset state |
| any physical device (serial not starting with `emulator-`) | personal data |

Safety checks, in order. Stop at the first that fails.
1. `--device` is given and starts with `emulator-`.
2. `adb devices -l` lists `--device` as `device` (online).
3. `adb -s <serial> emu avd name` returns an AVD name that is not protected.
4. **No other device is online.** List any other serial and its AVD name, and ask the user to shut those devices down themselves. Do not shut them down, and do not continue. (`ANDROID_SERIAL` should limit the run to one device, but this check does not rely on it. `run_gradle.sh` repeats checks 1–4 just before it starts Gradle.)
5. Ask the user to confirm with AskUserQuestion: device serial, AVD name, and "the app and its data will be uninstalled from this device".

| Artefact | Path |
|---|---|
| Results | `android/app/build/outputs/androidTest-results/connected/debug/TEST-*.xml` |

Parse with `--junit-task :app:connectedDebugAndroidTest`, `--since` and `--expect-device <serial>`. A `DEVICE MISMATCH` line is a **Failed** safety check, reported first.

Known caveat: `ProductionContentIntegrityTest` returns early when no content release is installed. On a fresh AVD it passes without checking anything, so report it as *Passed (vacuous: no content installed)*. Never cite it as content-integrity evidence.

Does not prove: release behaviour, TalkBack, other API levels.

## `release`

Builds the unsigned, R8-optimized release APK and analyzes it.
- Record the `android analyze` output (size and dex) and `grep -c '^com.nextdaymobilestudio' android/app/build/outputs/mapping/release/mapping.txt` (app-package entries kept by R8, F-11).
- `lintRelease` is not run.
- **Does not prove:** runtime behaviour of the release build. The APK is unsigned and is never installed by this skill.

## `content`

The test step of `.github/workflows/content-pipeline-ci.yml`, except that it uses unittest's default pattern `test*.py` instead of CI's `-p "test_*.py"`, so the pre-approved rule needs no wildcard. Today both select the same file (`test_build_schema3.py`). The CI `py_compile` and the build-and-validate smoke step are not run. Say so under *Not run*. Local Python is 3.9, while CI uses 3.13, so record the version (`python3 --version`), and treat a failure that only occurs on one version as *Needs verification*.

## `remote`

Records CI results for a pushed head. Runs nothing locally.
- With a PR number: `gh pr view <n> --json number,state,headRefOid,headRefName`, then `gh pr checks <n>`.
- Without one: `gh run list --branch <current branch> --limit 5 --json databaseId,headSha,status,conclusion,workflowName,createdAt`.
- Compare the run's `headSha` with local `HEAD`. If they differ, say which commit CI checked.
- Pending checks are **Not run yet**, never Passed.
