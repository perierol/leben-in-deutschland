# F-09 · RED phase evidence

- **Date:** 2026-11-05
- **Branch:** `fix/android-accessible-answer-feedback`, based on `e43187f` (= `main`)
- **Production code:** unchanged. Only `StudySessionScreenTest.kt` was modified, adding two tests.
- **Raw output:** [`red-instrumentation-raw.txt`](red-instrumentation-raw.txt)

## Environment

| Item | Value |
|---|---|
| Test device | **Dedicated** AVD `medium_phone`, serial `emulator-5556`, created for this run with `android emulator create medium_phone` |
| Image | `google/sdk_gphone64_arm64/emu64a:16` (API 36, arm64-v8a), locale `en-US` |
| Untouched device | `emulator-5554` (`Pixel_9a`). Nothing was installed on it, uninstalled from it or cleared on it. |
| Android CLI | 1.1.16717390 |
| Host | macOS (Darwin 24.6.0), JDK Temurin 25.0.3 |

Because the locale is `en-US`, `R.string.study_correct` and `R.string.study_incorrect` resolve from `values-en` ("Correct", …).

## Commands

```bash
git switch -c fix/android-accessible-answer-feedback
android emulator create medium_phone
android emulator start --headless --cold medium_phone            # -> emulator-5556

cd android
./gradlew assembleDebug assembleDebugAndroidTest                  # BUILD SUCCESSFUL

# Install only on the dedicated device. connectedDebugAndroidTest is deliberately
# not used: it targets every connected device and uninstalls the app afterwards.
adb -s emulator-5556 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

T=com.nextdaymobilestudio.lebenindeutschland.feature.studysession.StudySessionScreenTest
adb -s emulator-5556 shell am instrument -w -r \
  -e class "$T#submittedWrongAnswerExposesStateDescriptions,$T#explanationCardIsPoliteLiveRegion,$T#submittedAnswerShowsCollapsedMemoryKeyAndExpandedExplanation" \
  com.nextdaymobilestudio.lebenindeutschland.test/androidx.test.runner.AndroidJUnitRunner
```

## Result: `Tests run: 3, Failures: 2`

| Test | Role | Result |
|---|---|---|
| `submittedWrongAnswerExposesStateDescriptions` | new, F-09 | ❌ FAIL (expected) |
| `explanationCardIsPoliteLiveRegion` | new, F-09 | ❌ FAIL (expected) |
| `submittedAnswerShowsCollapsedMemoryKeyAndExpandedExplanation` | existing, control | ✅ PASS. Shows the harness, fixture and submitted state render correctly, so the failures above come from missing semantics and not from the test setup. |

### Expected vs actual

**1. `submittedWrongAnswerExposesStateDescriptions`.** State: selected `B`, submitted, correct answer `A`, option `C` unselected.

- **Expected:** `A` has StateDescription "Correct", `B` has the `study_incorrect` text, and `C` has no StateDescription.
- **Actual:** the test fails at the first assertion (`StudySessionScreenTest.kt:166`):

```
java.lang.AssertionError: Failed to assert the following: (StateDescription = 'Correct')
Semantics of the node:
Node #41 ... Tag: 'study-session-answer-A'
IsContainer = 'true'
Role = 'RadioButton'
Selected = 'false'
Text = '[A, Antwort A, Cevap A]'
[Disabled]
MergeDescendants = 'true'
```

This confirms F-09. The correct answer is exposed to accessibility services only as an unselected, disabled radio button, with nothing marking it as correct. Because the run stops at the first failure, the `B` and `C` assertions were **not reached**. They will be exercised in the GREEN phase.

**2. `explanationCardIsPoliteLiveRegion`.** State: selected `B`, submitted.

- **Expected:** the `study-session-explanation` node has `LiveRegion = Polite`.
- **Actual:** the test fails at `StudySessionScreenTest.kt:194`:

```
java.lang.AssertionError: Failed to assert the following: (LiveRegion = 'Polite')
Semantics of the node:
Node #139 ... Tag: 'study-session-explanation'
IsContainer = 'true'
Shape = 'RoundedCornerShape(... 20.0.dp ...)'
Has 3 children, 6 siblings
```

This confirms F-09. The result card has no live region, so TalkBack does not announce the verdict when it appears.

## Limitations

- Only one device configuration (API 36, en-US, default font scale) was used. RTL (`ar`) and German locale runs were not performed.
- No TalkBack session, `android layout` dump or screenshot of the submitted state was captured in this phase. The tests render in a test activity, not in the app's own screens. These are planned for GREEN (before/after).
- The Studio preview render for `StudySessionScreen.kt` still fails, because Studio needs a Gradle sync to resolve Coil. It was not retried.
- The new tests are not run by CI (F-06).
