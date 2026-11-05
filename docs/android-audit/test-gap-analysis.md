# Android Test Gap Analysis

Audit date: 2026-11-05. The coverage map below comes from reading the tests. No coverage tool such as Kover or JaCoCo was run, and the project has none configured.

## Current state (measured)

| Item | Value | Source |
|---|---|---|
| Unit test files | 17 (incl. 1 placeholder, plus a fixture) | `android/app/src/test` |
| Unit tests executed | **67 passed, 0 failed, 0 skipped** | `./gradlew testDebugUnitTest`, 2026-11-05, JUnit XML in `app/build/test-results` |
| Instrumented test files | 21 (incl. 1 placeholder) | `android/app/src/androidTest` |
| Instrumented tests executed | **Not run in this audit** (compiled only: `assembleDebugAndroidTest` succeeded) | See README, "Checks not run" |
| Instrumented tests in CI | **None** | `.github/workflows/android-ci.yml:50` |
| Unit test libraries | `junit:4.13.2` only | `android/app/build.gradle.kts:82` |
| Coroutine test support | None (`runBlocking` everywhere) | e.g. `MockExamCoordinatorTest.kt:25` |

## Coverage map

| Production class | Unit | Instrumented | What the tests actually assert |
|---|---|---|---|
| `MockExamViewModel` | — | — | **Nothing.** Screen tests use hand-built `uiState`. |
| `StudySessionViewModel` | — | — | **Nothing** |
| `ContentBootstrapViewModel` | — | — | **Nothing** |
| `ExamHistoryViewModel` | — | — | **Nothing** |
| `HomeRoute` / `ExamHistoryDetailRoute` (logic in composables) | — | — | **Nothing** |
| `LanguageSelectionViewModel`, `StateSelectionViewModel` | ✓ | screen | Synchronous state |
| `MockExamCoordinator` | ✓ (7) | — | 30+3 selection, uniqueness, deadline, restore, idempotent submit |
| `StudySessionCoordinator` | ✓ (10) | integrity | Resume, shuffle, wrong-answer mode, clear-on-complete |
| `ContentBootstrapCoordinator` | ✓ (5) | — | Offline first launch → Error; offline with cache → Ready(refreshFailed) |
| `ContentSyncCoordinator`, `ContentPackageMapper`, `CanonicalJson` | ✓ | — | Hash tampering, mismatch rejection, no writes on invalid package |
| `RoomLocalContentRepository` / `ContentDao` | — | ✓ (6) | Atomic install and activation, eligible IDs, progress upsert and clear. **Not covered: general/state ID queries used by the mock exam.** |
| Migrations 1→2, 2→3, 3→4 | — | ✓ (3) | Schema validates and content survives. **Not covered: user progress and history rows, full chain.** |
| DataStore preference repositories | ✓ | — | Round-trip and clear. **Not covered: corrupt JSON.** |
| `FirestoreContentDataSource` | — | — | Nothing (thin adapter; acceptable with fakes upstream) |
| Navigation (`LebenInDeutschlandApp`) | — | — | Nothing |

## Gaps by area

### Coroutines, Flow and time
- There is no `kotlinx-coroutines-test`, `MainDispatcherRule`, virtual time or Turbine. This blocks every `viewModelScope` test (F-07).
- The 60-minute timer, auto-submit and restore-after-deadline need `advanceTimeBy`. The VM already accepts an injected `currentTimeMillis`, so it is testable once the toolchain is added.

### Room
- Mock-exam DAO queries `getGeneralQuestionIds` and `getStateQuestionIds` are only tested through a fake (F-23).
- No test covers "latest attempt wins" for the wrong-answer state at the SQL level.
- Migrations don't assert that `study_question_progress` and `mock_exam_*` rows survive, and there is no 1→4 chain test (F-22).
- Nothing covers progress across a release change (F-02).

### Offline and sync
- Covered: first launch offline, and cached content with a failed refresh.
- Missing: cached content with a refresh that hangs forever (F-01), and a cancelled Firestore Task (F-13).
- Missing: `syncFailureAfterGermanInstall_doesNotActivate` (partial install).

### Critical business rules
- Pass thresholds: only 15 and 17 are tested, on the UI model. The 14 and 16 boundaries and the duplicated history model are untested (F-24).
- Wrong-answer lifecycle: "correct retry removes" is tested only on the fake. "Restart after completion" is locked in a way that deletes wrong answers (F-03).
- Mock exam restore edge cases: wrong question count, out-of-range index, state change, and corrupt JSON.

### UI and accessibility
- Screen and localization tests exist for every feature, which is a good base.
- Missing: semantics assertions for correct and wrong state (F-09), the mock scroll reset (F-14), RTL layout direction (F-16), font scale 200%, and screenshot tests.

### Test quality
- `ExampleUnitTest` and `ExampleInstrumentedTest` are placeholders and should be deleted.
- `ProductionContentIntegrityTest.kt:32` returns early on a device with no active release, so it passes without asserting anything. It should use `assumeTrue`.
- `ContentBootstrapCoordinatorTest.firstInstallFailureCanBeRetried` (`:77`) never retries.

## Prioritised tests to add

| # | Test | Level | Finding | Effort |
|---|---|---|---|---|
| 1 | Add `kotlinx-coroutines-test`, `MainDispatcherRule` and Turbine to the test toolchain | setup | F-07 | S |
| 2 | `MockExamViewModelTest.timerExpiry_autoSubmitsWithTimeoutFlag` | unit | F-07 | M |
| 3 | `MockExamViewModelTest.answerSavedAtDeadline_isCountedInResult` | unit | F-04 | M |
| 4 | `MockExamCoordinatorTest.crashAfterArchive_restoresCompletedExam` | unit | F-05 | S |
| 5 | `ContentBootstrapCoordinatorTest.hangingRefresh_withLocalContent_navigatesImmediately` | unit | F-01 | S |
| 6 | `StudySessionCoordinatorTest.restartAfterCompletion_preservesWrongAnswers` | unit | F-03 | S |
| 7 | `LocalContentDatabaseTest.stateQuestionIds_returnOnlySelectedState` | instr. | F-23 | S |
| 8 | `LocalContentDatabaseMigrationTest.migrateAll_1To4_preservesProgressAndHistory` | instr. | F-22 | S |
| 9 | `PassThresholdTest` with boundaries 14, 15, 16 and 17 for the UI and history models | unit | F-24 | S |
| 10 | Gradle Managed Device CI job running all of `androidTest` | CI | F-06 | M |

Sketch for #2:

```kotlin
@get:Rule val mainRule = MainDispatcherRule()

@Test fun timerExpiry_autoSubmitsWithTimeoutFlag() = runTest {
    var now = 100_000L
    val vm = MockExamViewModel("de", "BE", coordinator(clock = { now }), currentTimeMillis = { now })
    advanceUntilIdle(); vm.startExam(); advanceUntilIdle()
    now += MockExamCoordinator.EXAM_DURATION_MILLIS
    advanceTimeBy(1_001); advanceUntilIdle()
    val result = vm.uiState.value as MockExamUiState.Result
    assertTrue(result.submittedByTimeout)
}
```
