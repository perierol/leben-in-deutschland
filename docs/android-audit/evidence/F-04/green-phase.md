# F-04 · GREEN phase (2026-11-05)

- **Branch:** `fix/android-mock-exam-submit-integrity`, from `main` at `f7acff4`. Not committed.
- **Raw:** [`green-final-unit-raw.txt`](green-final-unit-raw.txt) (focused) and [`green-ci-reports-summary.txt`](green-ci-reports-summary.txt) (CI command). The console log is kept local because it contains absolute paths.
- **RED baseline:** [`red-phase.md`](red-phase.md). The F-05 fix had already been applied (see `../F-05/green-phase.md`).

## Production change

`feature/mockexam/MockExamViewModel.kt` only.

| Change | Lines | Requirement |
|---|---|---|
| On timeout, the timer no longer calls `actionJob?.cancel()`. It keeps the pending action, `join()`s it, then submits the **latest** `exam` instead of the snapshot captured at the tick. | `:188-201` | An answer tapped before the deadline is counted. An in-flight save is not cancelled. |
| A new `isSubmitting` flag is set by `confirmSubmit()` and by the timeout. While it is set, `selectAnswer`, `moveBy` and `confirmSubmit` return early, and a second timeout returns. `retry()` and `startNewExam()` clear it. | `:32`, `:41`, `:75`, `:120-121`, `:135`, `:149`, `:189-190` | No duplicate submission. No answer or navigation save can overwrite the completed snapshot after the pending save is released. |
| After joining, the timeout submit is skipped if the exam is already complete or the state is not `Ready` (for example `Error` from a failed save). | `:196-199` | No second submit. A failed save keeps today's `Error` → `retry()` path, which submits by timeout on restore. |
| The unchecked `(_uiState.value as MockExamUiState.Ready)` after an answer save was replaced by an `is Ready` check. | `:93-96` | A late save completion cannot turn `Result` into `Error`. |

Unchanged: early submission still requires confirmation (`requestSubmit`/`confirmSubmit`), restore after the deadline still submits by timeout, and the coordinator, UI, DataStore format and Room are untouched (ADR 0010 `:34-35`, `:49`).

## Results

| Run | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest --tests '*MockExam*'` | **21 tests, 0 failed** (DataStore 2, coordinator 9, ViewModel 10) |
| `./gradlew assembleDebug lintDebug testDebugUnitTest --stacktrace` | **BUILD SUCCESSFUL.** 82 unit tests in 17 classes, 0 failed, 0 skipped. Lint: 56 warnings, 0 errors. |

Lint compared with the F-09 baseline (55): the one extra warning is `NewerVersionAvailable` for `kotlinx-coroutines-test` 1.10.2 (added in #32, found by an online version check). The only lint item in `feature/mockexam` is the existing `EmptySuperCall` on `super.onCleared()`, which moved from `:230` to `:247`.

| Test | RED | GREEN |
|---|---|---|
| `answerTappedAtDeadlineWithPendingSave_isCountedInResult` | ❌ `:147` `expected:<1> but was:<0>` | ✅ |
| 3 F-05 regression tests | ✅ after the F-05 fix | ✅ |

The two guards listed in `red-phase.md` were revised after the review below.

## Review follow-up (2026-11-05)

A read-only review by `android-testing-reviewer` and `android-architecture-reviewer` found the guards weak and the new `isSubmitting` flag untested. Changes:

| Change | Why |
|---|---|
| `pendingSaveAtDeadline_archivesExactlyOnce` → `pendingSaveAtDeadline_submitsExactlyOnce`, asserting `recordCalls == 1` and `completedSaves == 1` (new fake counters) instead of history rows | The fake, `coordinator.submit` and Room all ignore a duplicate attempt ID, so a row count could never show a double submit |
| `lateSaveCompletion_neverReplacesResultWithError` **deleted** | It repeated the F-04 regression test with a weaker assertion. With the join, the save always completes while the state is `Ready`, so it could not reach the `is Ready` check it was named for |
| New `timeoutSubmitInFlight_ignoresAnswerAndNavigationTaps` | Covers the `isSubmitting` guard (see the correction below) |
| New `pendingSaveFailsAtDeadline_showsErrorThenRetrySubmitsByTimeout` | Covers the skip-if-not-`Ready` branch: `Error`, no history, then `retry()` submits by timeout. Uses a new `failSave()` on the gated fake |
| New `submitFailureThenRetry_acceptsAnswersAgain` and `startNewExamAfterResult_acceptsAnswers` | Cover the `isSubmitting` resets in `retry()` and `startNewExam()` |
| Gated fake resets its gate in `finally` | One-shot also when the held save fails or is cancelled |

**Correction.** This file previously said the `isSubmitting` window "cannot be opened in a test". That was wrong. The guard protects the whole timeout submit, not only the moment after the pending save finishes. Holding the completed-snapshot save with the gated fake opens the window under the existing `UnconfinedTestDispatcher`.

**Mutation check** ([`review-mutation-raw.txt`](review-mutation-raw.txt)). Each guard was removed in turn from a copy of the fixed `MockExamViewModel.kt`. The file was restored byte-identical afterwards. Each mutant fails exactly the test written for it:

| Mutant | Failing test |
|---|---|
| M1 `selectAnswer` without `\|\| isSubmitting` | `timeoutSubmitInFlight_ignoresAnswerAndNavigationTaps` ("Completed snapshot was overwritten") |
| M2 `retry()` without the reset | `submitFailureThenRetry_acceptsAnswersAgain` (`:298`, answer `B` ignored) |
| M3 `startNewExam()` without the reset | `startNewExamAfterResult_acceptsAnswers` (`expected:<A> but was:<null>`) |
| M4 timeout skip without the `!is Ready` check | `pendingSaveFailsAtDeadline_showsErrorThenRetrySubmitsByTimeout` (`Result` instead of `Error`) |

**Final results** ([`review-final-unit-raw.txt`](review-final-unit-raw.txt), [`green-ci-reports-summary.txt`](green-ci-reports-summary.txt)):

| Run | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest --tests '*MockExam*'` | **25 tests, 0 failed** (DataStore 2, coordinator 10, ViewModel 13) |
| `./gradlew assembleDebug lintDebug testDebugUnitTest --stacktrace` | **BUILD SUCCESSFUL.** 86 unit tests in 17 classes, 0 failed, 0 skipped. Lint 56 warnings, 0 errors (unchanged) |

## Not covered by a test

- **The `is Ready` check** (`:93-96`). With the join, the pending save completes while the state is still `Ready`, so no test reaches the old cast's failure. The change is defensive and verified by code reading.
- **`if (isSubmitting) return@launch` in the timer** (`:189`). The timer loop returns after its first timeout, and `submit()` cancels the timer, so no second timeout can reach it. It is defensive.
- **A save that never completes.** The timeout now waits for it, so the exam is not submitted until the save finishes or fails. Before the fix, such a save was cancelled and a stale snapshot submitted. A DataStore write is local, but there is no upper bound on the wait.
- No device check, and CI has not run this branch.
