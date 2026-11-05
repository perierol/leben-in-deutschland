# F-09 · GREEN phase evidence

> **Superseded.** This records iteration 1, where the whole card was the live region. The committed fix (`f88e250`) is iteration 2: [iteration2.md](iteration2.md).

- **Date:** 2026-11-05
- **Branch:** `fix/android-accessible-answer-feedback` (uncommitted), based on `e43187f`
- **Previous phase:** [red-phase.md](red-phase.md)

## Production change

There is one file: `android/app/src/main/java/com/nextdaymobilestudio/lebenindeutschland/feature/studysession/ui/StudySessionScreen.kt`

| Change | Where |
|---|---|
| `stateDescription` set to `study_correct` on the correct option and `study_incorrect` on a wrong selected option, only after submit. Unselected wrong options get none. | `AnswerOption`: `.semantics { }` after `.selectable(...)` |
| Decorative trailing `CheckCircle` (correct) or `Close` (wrong pick) icon, with `contentDescription = null` because the meaning is carried by `stateDescription` | `AnswerOption` row |
| `liveRegion = LiveRegionMode.Polite` with `mergeDescendants = true` on the result card | `ExplanationCard` |

- No new strings: both reuse existing resources that are present in all 21 locales.
- No new dependencies: `Icons.Filled.Close` is in `material-icons-core`, which the app already uses.
- Selection behaviour (`selectable`, `enabled = !isSubmitted`, `Role.RadioButton`), colours, the ViewModel, navigation, the mock exam and data layers are unchanged.
- The test code and assertions are identical to the RED phase. The RED stack-trace lines `:166` and `:194` still point to the same assertions. In RED, the run stopped at the first failing check (A), so the B and C checks were first exercised in GREEN.

## Commands and results

The device was the **dedicated** AVD `medium_phone` (`emulator-5556`, API 36, en-US). `emulator-5554` (Pixel_9a) was not used.

```bash
cd android
./gradlew assembleDebug assembleDebugAndroidTest                  # BUILD SUCCESSFUL; :app:compileDebugKotlin executed
                                                                  # APK 12:03:16 > source edit 12:03:14
adb -s emulator-5556 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

### 1. Targeted (the same 3 tests as RED) → [`green-targeted-raw.txt`](green-targeted-raw.txt)

```bash
adb -s emulator-5556 shell am instrument -w -r -e class \
  "$T#submittedWrongAnswerExposesStateDescriptions,$T#explanationCardIsPoliteLiveRegion,$T#submittedAnswerShowsCollapsedMemoryKeyAndExpandedExplanation" \
  com.nextdaymobilestudio.lebenindeutschland.test/androidx.test.runner.AndroidJUnitRunner
```

**Result: `OK (3 tests)`**

| Test | RED | GREEN |
|---|---|---|
| `submittedWrongAnswerExposesStateDescriptions` | ❌ (stopped at the check on A) | ✅ All three checks pass: A, B and C |
| `explanationCardIsPoliteLiveRegion` | ❌ | ✅ |
| `submittedAnswerShowsCollapsedMemoryKeyAndExpandedExplanation` (control) | ✅ | ✅ |

This run was against a build that still had a one-line code comment. The comment was then removed (no behaviour change), and run 2 below re-ran all three tests against the final source.

### 2. Full study-session UI suites → [`green-suite-raw.txt`](green-suite-raw.txt)

```bash
adb -s emulator-5556 shell am instrument -w -r -e class \
  "com.nextdaymobilestudio.lebenindeutschland.feature.studysession.StudySessionScreenTest,com.nextdaymobilestudio.lebenindeutschland.feature.studysession.StudySessionLocalizationTest" \
  com.nextdaymobilestudio.lebenindeutschland.test/androidx.test.runner.AndroidJUnitRunner
```

**Result: `OK (12 tests)`**: 11 in `StudySessionScreenTest` (9 existing and 2 new) and 1 in `StudySessionLocalizationTest`.

### 3. CI-equivalent JVM checks → `green-ci-checks.txt` (kept local; contains absolute local paths) + [`green-ci-reports-summary.txt`](green-ci-reports-summary.txt)

```bash
./gradlew lintDebug testDebugUnitTest
```

**Result:**
- `green-ci-checks.txt` holds only the Gradle console output: `BUILD SUCCESSFUL`, `exit=0`. Gradle does not print test or lint counts there.
- The counts come from the Gradle reports, summarised in `green-ci-reports-summary.txt`. The reports were written 12:03:48–12:03:56, after the final source edit at 12:03:14.
  - **67 unit tests, 0 failures, 0 errors, 0 skipped** (unchanged from the audit baseline).
  - **Lint: 55 warnings, 0 errors** (unchanged). This includes the `ModifierParameter` warning in `StudySessionScreen.kt`, which existed before this change.
- `compileDebugKotlin` and `lintDebug` were up to date or restored from cache in that run, because their inputs had not changed since the build in step 1. They were not re-executed from scratch.

## Final verification review (2026-11-05)

An independent read-only review by `compose-reviewer` covered the diff, the tests and all evidence logs. Its main claims were re-checked against the raw logs.

- **Scope:** one production file. The only other change is the test file. `.gitignore` and the untracked folders predate this PR.
- **Tests not weakened:** confirmed (see above).
- **Option semantics after submit** (from the GREEN dumps and code). All options are `[Disabled]`, `Role = RadioButton`.

  | Option | `stateDescription` |
  |---|---|
  | Correct | `study_correct` ("Correct" / "Richtig") |
  | Selected wrong | `study_incorrect` ("Not quite right" / "Noch nicht richtig") |
  | Unselected wrong | none (default state text kept) |

  Before submit, no option has a `stateDescription`.
- **Residual risks.** None of these were verified on a device.
  1. **The result card may not be announced.** It is the last `LazyColumn` item, so if it is below the viewport after submit, it may not be composed and the live region cannot fire. The option `stateDescription`s still carry the verdict.
  2. **Long announcements.** `mergeDescendants = true` merges the verdict, the "Explanation" label and the full explanation into one TalkBack stop, and the live region announces all of it. Possible fallback: put the live region on the verdict row only.
  3. **The user's pick is no longer called "selected".** A custom `stateDescription` replaces the default "selected" text, so the wrong pick is read as "Not quite right" without "selected".
  4. **Untested cases.** The tests do not cover the user picking the correct answer, or the absence of `stateDescription` before submit.

## Not executed

- Other instrumented suites, such as mock exam, Room and migrations. They are unaffected by this file, but were not run.
- An on-device check in the real app (an `android layout` before/after on the submitted study screen). A fresh install on the dedicated emulator needs onboarding and a content download first.
- TalkBack, RTL (`ar`), German locale and 200% font scale.
- Studio Compose preview render (still needs a Gradle sync for Coil).
