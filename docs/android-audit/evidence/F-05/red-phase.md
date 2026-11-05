# F-05 · RED phase (2026-11-05)

- **Branch:** `fix/android-mock-exam-submit-integrity`, from `main` at `f7acff4`. Production code unchanged.
- **Command:** `./gradlew :app:testDebugUnitTest --tests '*MockExam*'`, run from `android/`. Runs in CI as part of `testDebugUnitTest`.
- **Result:** 19 tests, 4 failed. The 15 existing mock-exam tests pass. One failure belongs to F-04 (see `../F-04/red-phase.md`).
- **Raw:** [`red-unit-raw.txt`](red-unit-raw.txt), trimmed from the JUnit XML.

## Decision recorded before the tests

After the fix (save the completed snapshot first, then archive), a crash between the two writes is recovered by `restore()`, which archives with `updateStudyProgress = false` (ADR 0012 `:26-28`). That exam's answers then never reach study progress. Restore cannot tell this case apart from an older-version snapshot, so switching to `true` would double-count progress for those.

Decision: **accept the gap for PR-D and do not assert it.** No test asserts `studyAnswerWrites` for the crash window. It is recorded as a follow-up on F-05.

## Failing tests

| Test | File | Failure | Why |
|---|---|---|---|
| `archiveFailure_restoreReturnsCompletedExam` | `MockExamCoordinatorTest.kt` | `:146` "Restored exam must be complete" | `MockExamCoordinator.kt:154-159` archives before saving. The archive throws, so the completed snapshot is never saved and `restore()` returns the incomplete exam. |
| `completedSaveFailure_leavesNoOrphanHistoryRow` | `MockExamCoordinatorTest.kt` | `:167` "History must not contain an attempt that restores as incomplete" | The archive succeeded before the completed save failed, so history holds an attempt the learner can still reopen and change. |
| `restartAfterArchiveCrash_showsResultMatchingHistory` | `MockExamViewModelTest.kt` | `:174` "Expected Result but was Ready(… answeredCount=1, remainingSeconds=3600 …)" | Same crash through the ViewModel. After `viewModelStore.clear()` (simulated process death), the new ViewModel resumes the exam instead of showing the result. |

## Test seams added (test code only)

- `FakeMockExamContentRepository.failNextRecord`: `recordMockExamResult` throws once.
- `FakeMockExamPreferencesRepository.failNextCompletedSave`: `saveExam` throws once for a completed snapshot.

Both are one-shot, so the following `restore()` runs against working fakes.
