# F-05 · GREEN phase (2026-11-05)

- **Branch:** `fix/android-mock-exam-submit-integrity`, from `main` at `f7acff4`. Not committed.
- **Raw:** [`green-unit-raw.txt`](green-unit-raw.txt), trimmed from the JUnit XML. RED baseline: [`red-phase.md`](red-phase.md).

## Production change

`feature/mockexam/MockExamCoordinator.kt`, `submit()` only: `saveExam(completed)` now runs **before** `archiveCompletedExam(...)`. Before the fix it ran after. Nothing else changed:
- `restore()` was not modified. It already re-archives a completed snapshot with `updateStudyProgress = false`, and the attempt ID makes that a no-op when history already has it.
- No Room schema, DataStore format, ViewModel or UI change.

## Results

| Run | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest --tests '*MockExam*'` | 21 tests, **1 failed**: only the F-04 regression test, which is expected to fail until F-04 is fixed |
| `./gradlew :app:testDebugUnitTest` (full unit suite) | 82 tests, **1 failed** (the same F-04 test), 0 skipped |

| F-05 regression test | RED | GREEN |
|---|---|---|
| `MockExamCoordinatorTest.archiveFailure_restoreReturnsCompletedExam` | ❌ `:146` restored exam incomplete | ✅ |
| `MockExamCoordinatorTest.completedSaveFailure_leavesNoOrphanHistoryRow` | ❌ `:167` orphan history row | ✅ |
| `MockExamViewModelTest.restartAfterArchiveCrash_showsResultMatchingHistory` | ❌ `:174` `Ready` instead of `Result` | ✅ |

## Behaviour in both failure scenarios (as asserted)

1. **Crash while archiving** (`failNextRecord`). The completed snapshot is already saved, and `submit` throws. On the next `restore()`, the exam is complete with `correctCount = 1`, and history has exactly one attempt with the same attempt ID. The ViewModel shows `Error` for the failed submit, and a recreated ViewModel shows `Result` with the counts history stores.
2. **Crash while saving the completed snapshot** (`failNextCompletedSave`). Nothing is archived. `restore()` returns the incomplete exam and history is empty, so the learner can finish the exam and submit it once.

Existing tests unchanged and passing, including:
- `submitRecordsAnsweredQuestionsAndLeavesBlanksUnchanged` (idempotent submit);
- `restoreBackfillsLegacyCompletedExamWithoutCountingProgressTwice` (ADR 0012 backfill);
- `restoreAfterDeadlineSubmitsByTimeout`.

## Known residual (accepted, not asserted)

In scenario 1, the answers from that exam do not reach study progress, because the restore backfill archives with `updateStudyProgress = false` (ADR 0012 `:26-28`). It happens whenever the archive fails after the completed snapshot is saved: process death, or a Room error followed by an in-app Retry. (Cancellation was a third trigger until the review follow-up below.) A follow-up on F-05 must decide whether to mark snapshots written by the current version so restore can record progress safely.

## Final run, after the F-04 fix

Raw: [`green-final-unit-raw.txt`](green-final-unit-raw.txt). The focused run had 21 tests, 0 failed. `assembleDebug lintDebug testDebugUnitTest` was BUILD SUCCESSFUL, with 82 unit tests, 0 failed, and 56 lint warnings (see `../F-04/green-phase.md`). All three F-05 regression tests still pass.

## Review follow-up: cancellation (2026-11-05)

The architecture review found that the save and the archive could be split by **cancellation**, not only by process death. For example, leaving the screen mid-submit clears the ViewModel and cancels `viewModelScope` after the completed snapshot is saved, which rolls back the Room transaction. `restore()` would then backfill the attempt without study progress. Before PR-D, the same cancellation lost no progress.

- **RED** ([`review-red-cancellation-raw.txt`](review-red-cancellation-raw.txt)): the new test `MockExamCoordinatorTest.cancellationDuringArchive_stillRecordsHistoryAndProgress` holds the archive with a new `holdNextRecord` gate, then cancels the submitting job. Result: `:196` `expected:<1> but was:<0>`. The completed snapshot was saved (the assertion before it passed), but history was empty.
- **Fix:** the two writes in `MockExamCoordinator.submit` now run inside `withContext(NonCancellable)`. The order is unchanged.
- **GREEN** ([`review-final-unit-raw.txt`](review-final-unit-raw.txt)): 25 focused tests, 0 failed. `assembleDebug lintDebug testDebugUnitTest` was BUILD SUCCESSFUL, with 86 unit tests, 0 failed, and 56 lint warnings (unchanged).

`NonCancellable` removes the cancellation trigger. A Room error during the archive, and process death, still lead to the limitation above (see `findings.md` F-05).

## Not verified

- No device or process-kill check. Both scenarios are simulated with one-shot throwing fakes.
- CI has not run this branch.
