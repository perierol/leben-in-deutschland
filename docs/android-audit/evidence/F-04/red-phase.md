# F-04 · RED phase (2026-11-05)

- **Branch:** `fix/android-mock-exam-submit-integrity`, from `main` at `f7acff4`. Production code unchanged.
- **Command:** `./gradlew :app:testDebugUnitTest --tests '*MockExam*'`, run from `android/`. Runs in CI as part of `testDebugUnitTest`.
- **Result:** 19 tests, 4 failed. The 15 existing mock-exam tests pass. The other two failures belong to F-05 (see `../F-05/red-phase.md`).
- **Raw:** [`red-unit-raw.txt`](red-unit-raw.txt), trimmed from the JUnit XML.

## Decision recorded before the test

ADR 0010 does not cover an answer whose save is still running at the deadline. Decision: **count it.** The learner tapped before the deadline, and the screen already showed the answer as selected and counted (`MockExamViewModel.kt:72-79`).

Out of scope: the timer checks once per second, so a tap up to about 1 s after the deadline is still accepted. This is existing behaviour, unchanged by PR-D, and is recorded as a follow-up next to F-25.

## Failing test

| Test | File | Failure | Why |
|---|---|---|---|
| `answerTappedAtDeadlineWithPendingSave_isCountedInResult` | `MockExamViewModelTest.kt` | `:147` `expected:<1> but was:<0>` (`result.correctCount`) | At timeout, `MockExamViewModel.kt:181` cancels the pending answer save, and `:177` submits the snapshot captured before the answer. The state reached `Result` with `submittedByTimeout = true` (the assertion before `:147` passed), but without the answer. |

## Guard tests (added before any fix)

Raw: [`guards-before-fix-raw.txt`](guards-before-fix-raw.txt). Production code unchanged: 21 tests, the same 4 regression failures, and both guards pass.

| Guard | Before fix | Protects against |
|---|---|---|
| `pendingSaveAtDeadline_archivesExactlyOnce` | ✅ | the fix submitting twice (extra ticks after `Result` must not archive again) |
| `lateSaveCompletion_neverReplacesResultWithError` | ✅ | the unchecked `as Ready` cast at `MockExamViewModel.kt:88` becoming reachable once the fix waits for the save instead of cancelling it |

After the F-05 fix (see `../F-05/green-phase.md`), `answerTappedAtDeadlineWithPendingSave_isCountedInResult` still fails with the same `expected:<1> but was:<0>`, and both guards still pass.

## Test seams

- `GatedMockExamPreferencesRepository` (already in `MockExamViewModelTest.kt`) holds the answer save. It was not moved, because both F-04 tests live in that file.
- Fake detail: when the timeout cancels the held save, the gate stays closed, so the timeout submit's own save also waits until `releaseSave()`. The test releases the gate after the tick, so it does not depend on this.
