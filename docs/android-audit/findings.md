# Android Audit — Findings Register

Audit date: 2026-11-05 · Branch: `chore/add-compose-reviewer` · Base commit: `e43187f`
Scope: `android/` module `:app`, `firestore.rules`, Android CI, ADRs 0001–0013, `docs/product-specification.md`.

Findings describe the code at `e43187f`. Later changes are added as dated **Update** notes. The original finding text is kept as the historical record.

Paths below are relative to `android/app/src/main/java/com/nextdaymobilestudio/lebenindeutschland/` unless they start with `android/`, `docs/`, `.github/` or `firestore.rules`.

## Legend

| Field | Values |
|---|---|
| Type | **Defect** (confirmed incorrect behaviour) · **Risk** (probable defect, needs a reproducer) · **Safeguard** (missing protection, nothing broken yet) · **Suggestion** |
| Severity | Critical · High · Medium · Low |
| Confidence | **Confirmed** (code path traced and/or reproduced) · **Probable** (code path traced, not reproduced) · **Needs verification** |
| Effort | S (< ½ day) · M (1–2 days) · L (> 2 days) |
| Source | Reviewer that raised it: ARCH / TEST / SEC / UI, or LEAD (main session). "Verified" means the main session re-read the cited lines. |

No Critical findings were identified. Nothing below was fixed during the audit.

---

## Ranked top 10

Ranked by production risk, then how well each fits a focused PR.

### F-01 · Returning users are blocked on the bootstrap screen until the network refresh ends
- **Category:** Architecture / Offline-first · **Type:** Defect · **Severity:** High · **Confidence:** Confirmed (verified) · **Effort:** S–M · **Source:** ARCH-1, UI-6
- **Evidence:**
  - `feature/contentbootstrap/ui/ContentBootstrapRoute.kt:27-31` only navigates when `!readyState.isRefreshing`.
  - `feature/contentbootstrap/ContentBootstrapCoordinator.kt:20` emits `Ready(isRefreshing = true)` for local content, then `:24` awaits `synchronize()`.
  - There is no `withTimeout` anywhere in `src/main`, verified with grep.
  - `core/content/remote/FirestoreContentDataSource.kt:43` uses `Source.SERVER`.
  - ADR 0006 says local content should be exposed "immediately while checking the server manifest in the background".
- **Impact:** On a slow network, captive portal or flaky connection, a learner who already has all content offline waits for the Firestore timeout before reaching Home. This contradicts the main product promise.
- **Fix:** Navigate on any `Ready`. Move the refresh into an application-scoped `CoroutineScope` (or WorkManager) and wrap it in `withTimeout`.
- **Regression test:** In `ContentBootstrapRoute`/coordinator, use a fake `ContentSynchronizer` that suspends forever while local content exists, and assert that `onContentReady` is called.
- **Verification:** Unit test, then a manual check on the emulator with networking disabled (`adb shell svc wifi disable; svc data disable`).

### F-02 · Publishing a new content release resets visible study progress and wrong answers
- **Category:** Architecture / Data integrity · **Type:** Risk · **Severity:** High · **Confidence:** Probable (verified mechanism; not reproduced) · **Effort:** M · **Source:** ARCH-3
- **Evidence:**
  - `core/database/entity/StudyQuestionProgressEntity.kt:10-16` keys progress by `release_id` and cascades from `questions`.
  - Reads always go through the active release: `feature/home/ui/HomeRoute.kt:32` and `feature/studysession/StudySessionCoordinator.kt:29`.
  - `core/content/sync/ContentSyncCoordinator.kt:85` activates the new release without carrying progress over.
  - `RoomLocalContentRepository.deleteRelease` is called only from `LocalContentDatabaseTest.kt:134`, so old releases are never deleted in production.
- **Impact:** The first content update after launch makes every learner's progress and Wrong Answers list look empty. Old releases also pile up on disk forever. Deleting them naively would also break exam-history detail, which looks questions up by `summary.releaseId` (`feature/examhistory/ui/ExamHistoryRoute.kt:48-52`).
- **Fix:** First decide on a progress-carry-over policy (ADR). Then, in the activation transaction, copy progress rows whose `question_id` still exists into the new release. Define retention for old releases that keeps the rows exam history references.
- **Regression test:** Instrumented test `progressSurvivesReleaseActivation`: record progress on R1, install and activate R2, and assert `observeStudyProgress` returns the same counts.
- **Verification:** Instrumented Room test. A manual check against a Firestore emulator is optional.

### F-03 · Finishing the catalogue and starting again wipes the Wrong Answers list
- **Category:** Business logic · **Type:** Defect (conflict between ADRs) · **Severity:** Medium · **Confidence:** Confirmed (verified); product intent needs confirming · **Effort:** S · **Source:** TEST-8, LEAD
- **Evidence:**
  - `feature/studysession/StudySessionCoordinator.kt:46-56` calls `clearStudyProgress` when every eligible question has been answered.
  - The DAO query at `core/database/dao/ContentDao.kt:296-303` deletes all rows for the release, language and state.
  - The wrong-answer set comes from the same table (`StudySessionCoordinator.kt:71-75`, ADR 0009 line 18). ADR 0008 lines 26-27 accepted the clear before ADR 0009 existed.
  - The test `completedProgressIsClearedBeforeStartingAgain` locks this behaviour in.
- **Impact:** A learner who finishes all 310 questions and starts again loses every question they still answer wrongly. Those are exactly the questions they need to review before the exam.
- **Fix:** Keep wrong answers in a separate table or column (e.g. `last_result`, `in_current_pass`), or clear only `is_correct = 1` rows. Update ADR 0008.
- **Regression test:** `restartAfterCompletion_preservesWrongAnswers` in `StudySessionCoordinatorTest`, plus a Room-level test.
- **Verification:** Unit and instrumented tests.

### F-04 · A mock-exam timeout can cancel an in-flight answer and submit a stale snapshot
- **Status (2026-11-05):** fix **merged in #33** (`3422345`). Android CI passed on the PR head `ac2cb54`. Regression test `answerTappedAtDeadlineWithPendingSave_isCountedInResult` went RED → GREEN. Five guard tests cover a single submission, taps during the timeout submit, a failed pending save, and the `isSubmitting` resets. Each guard was mutation-checked. See `evidence/F-04/green-phase.md`.
  - **Implementation differs from the fix below:** no `Mutex`. The timeout `join()`s the pending save and submits the latest snapshot, and an `isSubmitting` flag blocks further answer, navigation and submit actions once a submission starts.
  - **Decision:** an answer tapped before the deadline counts, even if its save completes after it (ADR 0010 does not cover this case).
  - **Remaining:** taps up to about 1 s after the deadline are still accepted, because the timer checks once per second (follow-up, see F-25). A save that never completes now delays the timeout submit indefinitely.
  - **Post-merge audit (2026-11-05, `/android-audit range f7acff4..3422345`; local report, not committed):** no regression in the F-04 behaviour. Test follow-ups, not registered as separate findings:
    - the `isSubmitting` guards in `confirmSubmit` and `moveBy` are not tested in isolation;
    - one test asserts that the UI stays `Ready` during the timeout submit, which needs a UX decision.
- **Category:** Concurrency / Data integrity · **Type:** Risk · **Severity:** Medium · **Confidence:** Probable (verified code path) · **Effort:** S–M · **Source:** UI-3, ARCH-5, TEST-3
- **Evidence:**
  - `feature/mockexam/MockExamViewModel.kt:177-184` captures `exam`, calls `actionJob?.cancel()` and submits `loadedExam`.
  - `selectAnswer` assigns `exam = updatedExam` only after the save completes (`:82-87`).
  - `:88` uses an unchecked cast, `(_uiState.value as MockExamUiState.Ready)`, which fails if the state became `Result`.
- **Impact:** An answer tapped in the last second can be left out of the graded result, or a generic Error screen can flash.
- **Fix:** Serialise exam mutations with a `Mutex` (or a single actor). On timeout, `join()` the pending save instead of cancelling it. Use `_uiState.update { (it as? Ready)?.copy(isSaving = false) ?: it }`.
- **Regression test:** `answerSavedAtDeadline_isCountedInResult`, using a fake prefs repository whose `saveExam` suspends on a `CompletableDeferred`, with `runTest` and `advanceTimeBy`.
- **Verification:** Unit test. This depends on F-07 (coroutines-test) being available.

### F-05 · A mock exam is archived to Room before its completed snapshot is persisted
- **Status (2026-11-05):** fix **merged in #33** (`3422345`). Android CI passed on the PR head `ac2cb54`. `MockExamCoordinator.submit` now saves the completed snapshot before archiving, and runs both writes inside `NonCancellable` so a cancelled caller cannot split them. Four regression tests went RED → GREEN (`archiveFailure_restoreReturnsCompletedExam`, `completedSaveFailure_leavesNoOrphanHistoryRow`, `restartAfterArchiveCrash_showsResultMatchingHistory`, `cancellationDuringArchive_stillRecordsHistoryAndProgress`). See `evidence/F-05/green-phase.md`.
  - **Remaining limitation (accepted, not asserted by a test):** study progress may not receive the exam's answers if archiving fails after the completed snapshot is saved.
    - **Triggers:** process death between the two writes, or any exception from the Room archive (for example a SQLite or disk error) followed by Retry in the app. Cancellation is no longer a trigger (`NonCancellable`). A missing question is not one either, because `submit` resolves all questions before the first write.
    - **Mechanism:** the next `restore()` re-archives the attempt with `updateStudyProgress = false`, following ADR 0012's backfill rule (`:26-28`). That rule was written for snapshots from older app versions, but restore cannot tell them apart. Switching to `true` would double-count progress for older snapshots.
    - **Effect:** history and the result screen are correct, but **that exam's answers never reach study progress**, so its wrong answers do not enter wrong-answer review. This contradicts ADR 0010 `:57-58` and extends ADR 0012's backfill rule beyond its stated scope.
  - **Follow-up:**
    - Add a defaulted field to `MockExamSnapshot` that marks snapshots written by the current version. The serializer already ignores unknown keys and encodes defaults, so older snapshots still decode. Restore could then record progress for current-version snapshots only.
    - Update ADR 0012. A product decision is needed on when older-version handling can be retired.
  - **Post-merge audit (2026-11-05, `/android-audit range f7acff4..3422345`; local report, not committed):**
    - It confirmed the remaining limitation above, without new triggers.
    - It found that the `restore()` backfill path has no Room-level test (**F-33**).
    - It found a low regression risk from the `NonCancellable` submit (**F-34**).
- **Category:** Data integrity · **Type:** Defect (crash-window ordering) · **Severity:** Medium · **Confidence:** Confirmed (verified ordering) · **Effort:** S · **Source:** ARCH-2
- **Evidence:**
  - `feature/mockexam/MockExamCoordinator.kt:154-159` calls `archiveCompletedExam(...)` and then `saveExam(completed)`.
  - The idempotency guard at `core/database/repository/RoomLocalContentRepository.kt:212-213` silently returns `false` on a second archive.
- **Impact:** If the process dies between the two writes, `restore()` brings back an incomplete exam. The learner can keep answering. On the second submit the result screen shows a score that history does not store.
- **Fix:** Save the completed snapshot first. `restore()` already re-archives complete snapshots idempotently (`MockExamCoordinator.kt:34-39`).
- **Regression test:** A coordinator test with a fake prefs repository that throws after `recordMockExamResult`. Then `restore()` and assert the exam is complete with the same counts.
- **Verification:** Unit test.

### F-06 · Instrumented tests (Room, migrations, Compose UI) never run in CI
- **Category:** Testing / CI · **Type:** Safeguard · **Severity:** High (engineering) · **Confidence:** Confirmed (verified) · **Effort:** M · **Source:** TEST-5
- **Evidence:** `.github/workflows/android-ci.yml:50` runs `assembleDebug lintDebug testDebugUnitTest` only. That leaves out 21 files in `src/androidTest`: `LocalContentDatabaseTest`, `LocalContentDatabaseMigrationTest` and all screen tests.
- **Impact:** The only tests for migrations, DAO queries and transaction atomicity never gate a merge. A broken migration would ship and could lose user progress, the one piece of data that cannot be re-downloaded.
- **Fix:** Add a Gradle Managed Device job (`pixel6api34DebugAndroidTest`) with an ATD image, or move the Room tests to Robolectric so they run in `testDebugUnitTest`.
- **Regression test:** The CI job itself. Prove it gates by temporarily breaking `MIGRATION_3_4` in a throwaway branch.
- **Verification:** CI run log.

### F-07 · No ViewModel is unit-tested, and the test toolchain can't test them
- **Update (2026-11-05, after #32 and #33): partly resolved.**
  - **#32** (`f7acff4`) added `kotlinx-coroutines-test` (`android/app/build.gradle.kts:93`), `testing/MainDispatcherRule.kt` and `MockExamViewModelTest`.
  - **#33** (`3422345`) extended `MockExamViewModelTest` to 13 tests. They cover timer expiry, restore after the deadline, double-submit guards, a pending save at the deadline, and the retry and new-exam resets.
  - **Still untested:** `StudySessionViewModel`, `ContentBootstrapViewModel` and `ExamHistoryViewModel`. A grep over `src/test` and `src/androidTest` on 2026-11-05 found no references.
  - Turbine and Robolectric are still absent.
  - The evidence below describes the code at `e43187f`.
- **Category:** Testing · **Type:** Safeguard · **Severity:** High (engineering) · **Confidence:** Confirmed · **Effort:** S (toolchain) + M (tests) · **Source:** TEST-1, TEST-2, TEST-10, ARCH-10
- **Evidence:**
  - Unit-test dependencies are only `junit` (`android/app/build.gradle.kts:82`). `kotlinx-coroutines-test`, Turbine and Robolectric are absent.
  - Coordinator tests use `runBlocking` (e.g. `MockExamCoordinatorTest.kt:25`).
  - `MockExamViewModel`, `StudySessionViewModel`, `ContentBootstrapViewModel` and `ExamHistoryViewModel` have no tests. The screen tests render stateless composables with hand-built state.
- **Impact:** The 60-minute timer, auto-submit, restore-after-deadline, the double-submit guards and error mapping are all unprotected.
- **Fix:** Add `kotlinx-coroutines-test` (and optionally Turbine) plus a `MainDispatcherRule`. Then write `MockExamViewModelTest` first.
- **Regression test:** `timerExpiry_autoSubmitsWithTimeoutFlag`, `restoreAfterDeadline_submitsByTimeout`, `selectAnswer_whileSaving_isIgnored`.
- **Verification:** `./gradlew testDebugUnitTest` shows the new tests.

### F-08 · Data access inside composables: unhandled exceptions and swallowed cancellation
- **Category:** Architecture / Error handling · **Type:** Risk · **Severity:** Medium · **Confidence:** Confirmed (code), crash not reproduced · **Effort:** S–M · **Source:** UI-5, ARCH-7
- **Evidence:**
  - `feature/home/ui/HomeRoute.kt:26-55` queries Room in `produceState` with no `catch`, and silently returns when there is no active release (`:32`).
  - `feature/examhistory/ui/ExamHistoryRoute.kt:42-61` catches `Exception`, which also catches `CancellationException`.
  - ADR 0003 says "Compose screens do not access databases".
- **Impact:** A Room failure on Home crashes the app instead of showing a degraded state. Neither route can be unit-tested.
- **Fix:** Add `HomeViewModel` and `ExamHistoryDetailViewModel` exposing `StateFlow` via `stateIn(WhileSubscribed(5_000))`, with `.catch` and cancellation rethrown.
- **Regression test:** `HomeViewModelTest.repositoryFailure_emitsSafeDefault`.
- **Verification:** Unit tests.

### F-09 · After submit, options show which one is correct or wrong only by colour, and nothing is announced to TalkBack
- **Status (2026-11-05):** fix committed in `f88e250` on `fix/android-accessible-answer-feedback` (not pushed). Automated semantics tests pass (see `evidence/F-09/iteration2.md`). **Manual TalkBack verification: NOT VERIFIED.**
- **Note:** the fix and tests below are the *original plan*, and line numbers refer to the code before the fix. The committed implementation differs in three ways:
  - Only the verdict row is a Polite live region; the card is no longer merged.
  - The list auto-scrolls to the result once per question.
  - `explanationCardIsPoliteLiveRegion` was replaced by `onlyVerdictIsPoliteLiveRegion`, and `submittingAnswerRevealsVerdictWithoutManualScroll` and `verdictRevealDoesNotRepeatOnRecomposition` were added.

  See `evidence/F-09/iteration2.md`.
- **Category:** Accessibility · **Type:** Safeguard · **Severity:** Medium · **Confidence:** Confirmed (code); TalkBack output not exercised on device · **Effort:** S · **Source:** UI-2. Re-verified 2026-11-05 by an independent `compose-reviewer` pass, with the main session re-reading the cited lines.
- **Evidence** (`feature/studysession/ui/StudySessionScreen.kt`):
  - **Option styling, `:344-361`.** After submit, options differ only by container and border colour:
    - correct option: `secondaryContainer` with a `secondary` border;
    - wrong pick: `errorContainer` with an `error` border;
    - every other option: `surface` with an `outlineVariant` border.
    - No option gets an icon or text that marks it correct or wrong.
  - **Option semantics, `:363-373`.** The only semantics are `.selectable(selected = isSelected, enabled = !isSubmitted, role = Role.RadioButton)` inside a `selectableGroup()` (`:167`). The file contains no `stateDescription`, `contentDescription` or `liveRegion` (checked with grep).
  - **Result card, `ExplanationCard` at `:447-501`.**
    - It does give a non-colour verdict: a `CheckCircle` or `Warning` icon (`contentDescription = null`) plus the text `study_correct` ("Richtig") or `study_incorrect` ("Noch nicht richtig").
    - It has no `liveRegion`, no `heading()` and no focus request, and it never names the correct option.
    - It is the last `LazyColumn` item, added only after submit (`:236-240`), and nothing scrolls it into view.
  - **Device check.** `android layout` on emulator-5554 shows the options exposing `CHECKABLE` semantics, but no state description.
  - **Corrections to the earlier version of this entry:**
    - The verdict itself is not colour-only, because the card has an icon and text. What is colour-only is *which option* is correct and which pick was wrong.
    - The card ends at `:501`, not `:500`.
    - "Below the fold" is inferred from the code. Whether it happens depends on question length, font scale and device.
    - The mock-exam timer colour (`MockExamScreen.kt:349`) was not part of this re-verification. It is carried over as a separate, unverified note.
- **Accessibility impact:**
  - **TalkBack users:**
    - Activating "check answer" produces no spoken result. The user has to find the card by swiping to the end of the list.
    - A wrong pick and a correct pick are both announced only as "selected".
    - The correct option, when the user picked wrong, is announced with no indication that it is correct.
    - All options are disabled after submit, so they are read as dimmed.
  - **Colour-vision-deficient users:** they can see the verdict on the card but cannot reliably tell which option was correct.
  - Answer feedback is the core learning interaction of the app.
- **Theme assumption (corrected):** `ui/theme/Theme.kt` defines only a `darkColorScheme`, and the app has no light theme. Contrast checks apply to the dark scheme only: the option borders `secondary`/`error` against `outlineVariant`, and the containers against `surface` (WCAG 1.4.11 non-text contrast). If a light theme is added later, it needs the same check.
- **Recommended minimal fix:** no production code was changed for this entry. Reuse the existing strings `R.string.study_correct` and `R.string.study_incorrect`, which are present in all 21 `values*` locales, so **no new translations are needed**. A spoken prefix such as "your answer" would be optional and would need one new string in 21 locales.
  ```kotlin
  val stateText = when {
      isSubmitted && isCorrectAnswer -> stringResource(R.string.study_correct)
      isSubmitted && isSelected -> stringResource(R.string.study_incorrect)
      else -> null
  }
  // option Surface modifier, after .selectable(...)
  .semantics { stateText?.let { stateDescription = it } }

  // option Row: visual non-colour cue (decorative; meaning is carried by stateDescription)
  if (isSubmitted && (isCorrectAnswer || isSelected)) {
      Icon(
          imageVector = if (isCorrectAnswer) Icons.Filled.CheckCircle else Icons.Filled.Close,
          contentDescription = null,
      )
  }

  // ExplanationCard Surface modifier
  .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
  ```
  Optional follow-up: move the `LazyListState` up to the screen and call `animateScrollToItem` on the card from a `LaunchedEffect(state.isSubmitted)`.
- **Regression tests** (Compose UI, not yet implemented). Both go in `android/app/src/androidTest/.../feature/studysession/StudySessionScreenTest.kt` and use the existing `setScreen` and `readyState` helpers, whose fixture has `correctAnswerId = "A"`. No new dependencies are needed.
  1. **`submittedWrongAnswerExposesStateDescriptions`**: set up the screen with `readyState(selectedAnswerId = "B", isSubmitted = true)`, then assert:
     - `answer("A")` has `SemanticsProperties.StateDescription` equal to `getString(R.string.study_correct)`;
     - `answer("B")` has it equal to `getString(R.string.study_incorrect)`;
     - an unselected option has no state description (`SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)`).
  2. **`explanationCardIsPoliteLiveRegion`**: with the same state, `onNodeWithTag(EXPLANATION).performScrollTo()`, then assert `SemanticsProperties.LiveRegion` is `LiveRegionMode.Polite`.

  These run only on a device or emulator. CI does not run `androidTest` yet (see F-06).
- **Required verification:**
  - **Automated:** run the two tests above on an emulator with `./gradlew connectedDebugAndroidTest`. This uninstalls the app from the device, so use a dedicated emulator rather than one holding user data.
  - **Android CLI evidence (before and after):**
    - On the study screen with a submitted wrong answer, save `android layout --pretty -o layout-f09-{before,after}.json` and `android screen capture -o screen-f09-{before,after}.png`.
    - Run `android studio render-compose-preview ... --print-semantics` on a submitted-state preview. The current `StudySessionScreen.kt` preview failed to render because Studio did not resolve Coil, so sync Gradle in Studio first.
    - Store the outputs under `docs/android-audit/evidence/F-09/`.
  - **Manual TalkBack pass, in German and in `ar` (RTL):**
    - Choose a wrong option and tap "check answer".
    - Confirm the verdict is announced without navigating to it.
    - Confirm the correct option is identifiable by voice alone.
    - Note how the disabled options are read, and check the focus order.
  - **Visual:** check with Developer options → Simulate colour space (deuteranomaly) and at 200% font scale, to see whether the card ends up off-screen. Check contrast on the dark scheme only.

### F-10 · Documented stack and requirements differ from the implementation (Auth, Hilt, WorkManager)
- **Update (2026-11-05, after #29, `main` @ `e495240`):** this finding is **partly resolved**.
  - **Anonymous Firebase Authentication is now implemented.** `FirebaseAnonymousAuthenticator` signs in before the first content sync (wired in `FirebaseContentSynchronizerFactory.kt`), and `firebase-auth` is a dependency.
  - **Hilt and WorkManager are still not implemented** (no references in `android/app`), but the `main` README still lists both (`README.md:64-65`). DI remains the manual container.
  - The README no longer points to `firebase/` for the rules.
  - Progress is still local-only.
  - The "matches the privacy policy" statement in *Impact* below needs re-checking (see *Done well → Privacy*).
- **Category:** Requirements / Documentation · **Type:** Defect (documentation drift) · **Severity:** Medium · **Confidence:** Confirmed (verified) · **Effort:** S (docs) / L (implementing auth) · **Source:** LEAD, SEC-4, ARCH drift list
- **Evidence:**
  - A grep of `android/app/src` finds no `FirebaseAuth`, `signInAnonymously`, `Hilt`/`@Inject` or `WorkManager`.
  - None of them are in `android/app/build.gradle.kts:56-90`.
  - `README.md:32,47-50` and `docs/product-specification.md` (anonymous identity, around lines 120 and 300) require them. ADR 0003 says "Introduce Hilt", but DI is a manual container (`LebenInDeutschlandApplication.kt:21-42`).
  - `README.md:86` says the rules live in `firebase/`, but that directory is empty and the rules are at the repository root.
- **Impact:** This is not a bug today, because progress is local-only and that matches the privacy policy. It does mislead reviewers and future contributors, and it creates a trap: per-user cloud data added later under the current rules would ship without uid-scoped rules.
- **Fix:** Decide whether auth is in v1. If not, update the README, spec and ADR 0003 to say "manual DI; local-only progress; no auth in v1". Fix the rules path.
- **Regression test:** If per-user Firestore data is added, a Firestore emulator rules test (`@firebase/rules-unit-testing`).
- **Verification:** Doc review.

---

## Further findings

| ID | Category | Title | Type | Sev. | Conf. | Evidence | Fix | Effort | Src |
|---|---|---|---|---|---|---|---|---|---|
| F-11 | Build / Perf | Release R8 optimisation is limited to `androidx`, `kotlin` and `kotlinx`. App code and Firebase ship unshrunk and unobfuscated. | Defect (config) | Medium | Confirmed | `android/app/build.gradle.kts:37-42` `packageScope`. The release `mapping.txt` has **0** `com.nextdaymobilestudio` entries. `android analyze` reports 19.17 MB of uncompressed dex. Android docs say `packageScope` is for apps *adopting* R8 and needs `android.r8.gradual.support=true`, which is absent from `gradle.properties`. | Remove `packageScope` (use full R8 with keep rules) or widen it gradually, then smoke-test the release build. | M | LEAD, SEC-7 |
| F-12 | Architecture | Bootstrap ViewModel may capture the old language after a language change in Settings | Risk | Medium | Needs verification | `app/LebenInDeutschlandApp.kt:286-290`. `ContentBootstrapKey` is a `data object`, and the language comes from an async DataStore flow. | `data class ContentBootstrapKey(val languageCode: String)` | S | ARCH-4 |
| F-13 | Error handling | A cancelled Firestore Task leaves bootstrap stuck on Loading. Local lookup runs outside `try`. | Risk | Low | Needs verification | `FirestoreContentDataSource.kt:72-73`, `ContentBootstrapCoordinator.kt:16,32-33` | Map Task cancellation to `ContentSyncException`. Move the lookup inside `try`. | S | ARCH-6 |
| F-14 | UI | Mock exam keeps the previous question's scroll position | Risk | Medium | Probable | `feature/mockexam/ui/MockExamScreen.kt:275-338` has a `LazyColumn` without a state keyed by question. | `key(question.id)` or `scrollToItem(0)` on change | S | UI-1 |
| F-15 | Localisation | No `<plurals>`. Strings like "1 Fragen zum Wiederholen" are wrong for 1, and wrong for most counts in ru, uk, pl and ar. | Defect | Low | Confirmed | Lint `PluralsCandidate` ×27 (`lint-results-debug.xml`), `res/values/strings.xml:22,32,39,59,66,81,86,95` | Convert to `<plurals>` and `pluralStringResource` | M | UI-11, lint |
| F-16 | Accessibility / RTL | Memory-key arrow `→` is hardcoded LTR. German and translated text have no locale span for TTS. | Defect / Suggestion | Low | Confirmed / Needs verification | `StudySessionScreen.kt:292`, `ContentPackageMapper.kt:506` | AutoMirrored icon. `SpanStyle(localeList = LocaleList("de"))`. | S | UI-7 |
| F-17 | Security | Remote image URLs are not restricted to https and allowlisted hosts, and image sha256 is not verified | Safeguard | Low | Confirmed | `ContentPackageMapper.kt:365-367`, `StudySessionScreen.kt:437`, `MockExamScreen.kt:493` | Validate scheme and host in the mapper | S | SEC-1 |
| F-18 | Security | No network security config. Cleartext is allowed on API 24–27 (`minSdk 24`). | Safeguard | Low | Confirmed | Manifest has no `networkSecurityConfig` | Add `cleartextTrafficPermitted="false"` | S | SEC-2 |
| F-19 | Security / Cost | Public Firestore reads without App Check. **Update (2026-11-05, #29):** reads now require `request.auth != null` (`firestore.rules:14,19` on `main`). Anonymous accounts can be created by any client, so App Check stays a separate safeguard against scripted reads. Residual severity: Low. | Risk | Low–Med (audit) → Low | Probable | `firestore.rules:6,11` (at audit) | App Check (Play Integrity) and a budget alert | M | SEC-3 |
| F-20 | Architecture | `LocalContentRepository` has default implementations whose semantics differ from Room (no-op `clearStudyProgress`, `recordMockExamResult` returns `true` without storing, prefix-based state filter) | Safeguard | Medium | Confirmed | `core/database/repository/LocalContentRepository.kt:45-53,65,101-133` | Remove defaults. Split into Content, Progress and History repositories. Add contract tests. | M | ARCH-8 |
| F-21 | Data integrity | Release install is several transactions, and German is re-downloaded when only the selected language is missing | Safeguard | Low | Confirmed | `ContentSyncCoordinator.kt:67,82-85` | One `installAndActivate` transaction. Download only missing packages. | S | ARCH-9 |
| F-22 | Testing | Migration tests don't assert user progress or history survive. There is no 1→4 chain test. | Safeguard | Medium | Confirmed | `androidTest/.../LocalContentDatabaseMigrationTest.kt:20,61,104` | `migrateAll_1To4_preservesStudyProgressAndHistory` | S | TEST-6 |
| F-23 | Testing | `getGeneralQuestionIds` and `getStateQuestionIds` (mock-exam selection) are never exercised against real Room | Safeguard | Medium | Confirmed | Fake override at `MockExamCoordinatorTest.kt:228-237`. `ContentDao.kt` around lines 152-172. | Instrumented DAO test | S | TEST-4 |
| F-24 | Business logic | Pass thresholds are duplicated (`MockExamThresholds` vs literals 15 and 17), and the 14 and 16 boundaries are untested | Safeguard | Low | Confirmed | `core/database/model/MockExamResultRecord.kt:21-23` | Single constant plus a parameterised boundary test | S | TEST-7 |
| F-25 | Mock exam | Timer uses wall-clock time, so changing the device clock changes the remaining time | Risk | Low | Needs product decision | `MockExamViewModel.kt:21,213-215` | Prefer `elapsedRealtime` while the process is alive | M | UI-4 |
| F-26 | UI | The study session flashes a full-screen Loading state between questions, losing focus and scroll | Suggestion | Low | Confirmed | `StudySessionViewModel.kt:103-118` | Keep `Ready` and swap the question | S | UI-8 |
| F-27 | UI | Settings language change isn't guarded against double taps (duplicate writes and back-stack resets) | Risk | Low | Probable | `app/LebenInDeutschlandApp.kt:277-310` | Use an in-flight flag in the ViewModel | S | UI-9 |
| F-28 | Adaptability | No window-size handling or `widthIn(max)`. Exam history error has no retry. | Suggestion | Low | Confirmed | grep shows no `WindowSizeClass` or `widthIn(max`. `ExamHistoryScreen.kt:276-290`. | Constrain content width. Add retry. | M | UI-10 |
| F-29 | Testing | Placeholder tests. `ProductionContentIntegrityTest` passes trivially with no active release. `firstInstallFailureCanBeRetried` never retries. | Safeguard | Low | Confirmed | `ProductionContentIntegrityTest.kt:32`, `ContentBootstrapCoordinatorTest.kt:77`, `Example*Test.kt` | Use `assumeTrue`, write a real retry test, delete the placeholders | S | TEST-11 |
| F-30 | Security / Privacy | Backup rules are untouched templates. Full auto-backup includes the re-downloadable catalogue. | Suggestion | Low | Confirmed | `AndroidManifest.xml:10`, `res/xml/backup_rules.xml`, `data_extraction_rules.xml` | Document an explicit include/exclude policy | S | SEC-5 |
| F-31 | Supply chain | GitHub Actions are pinned by tag, not SHA. No Gradle dependency verification. | Suggestion | Low | Confirmed | `.github/workflows/android-ci.yml:35,38,44` | Pin SHAs. Add Dependabot. | S | SEC-6 |
| F-32 | UX | After process death, the study session reshuffles and shows a different unanswered question at the same index | Suggestion | Low | Confirmed (reproduced on emulator) | ADR 0007 lines 18-19, `StudySessionCoordinator.kt:62-68` | Persist the session order or seed | S | LEAD |
| F-33 | Testing | The mock-exam backfill path (`restore()` re-archives a completed snapshot with `updateStudyProgress = false`) is tested only against a fake, never against Room. Since #33 it is the recovery path for every crash between the two submit writes. *Added 2026-11-05 from the post-merge audit of #33; describes `3422345`.* | Safeguard | Medium | Confirmed | `feature/mockexam/MockExamCoordinator.kt:36-41`. The only Room-level call is `androidTest/.../LocalContentDatabaseTest.kt:230-231` (`recordMockExamResult(result)`, default `true`). Fake at `test/.../MockExamTestFixtures.kt:134-158`. Room at `core/database/repository/RoomLocalContentRepository.kt:207-240`. Room tests do not run in CI (F-06). | Instrumented test `recordMockExamResult_withoutProgress_writesHistoryOnlyAndIsIdempotent` in `LocalContentDatabaseTest`. Gate it in CI with F-06. | S | TEST (TR-1) |
| F-34 | Concurrency / Lifecycle | **Regression risk introduced by #33, not a confirmed bug.** The submit's two writes now run in `NonCancellable`, so they can outlive the ViewModel. If the learner leaves mid-submit and quickly returns, the new ViewModel's `restore()` may archive the attempt without study progress while the original submit is still running. The outcome is the same as the F-05 limitation. The reviewer judged that the original submit almost always wins. *Added 2026-11-05 from the post-merge audit of #33; describes `3422345`.* | Risk | Low | Needs verification | `feature/mockexam/MockExamCoordinator.kt:159-166`, with `restore()` at `:36-41`. `retry()` (`MockExamViewModel.kt:38-57`) and `startNewExam()` (`:132-143`) do not wait for the block, but they cannot be reached during a submit: Retry renders only for `Error`, and New exam only for `Result` (`feature/mockexam/ui/MockExamScreen.kt:110,116-119`). Not reproduced. | First confirm the ViewModel's lifetime under Navigation3 entry scoping. If the race is real, serialise exam persistence in `MockExamCoordinator` with a `Mutex` shared by `submit`, `restore`, `clear` and the answer and move saves. Test: `restoreDuringInFlightSubmit_waitsAndRecordsProgressOnce` (with the `holdNextRecord` gate). | S–M | ARCH (AR-2) |

## Done well (evidence-backed)

- **Content trust boundary:** SHA-256 is checked per chunk, per package and per catalogue over canonical JSON. Packages are fully validated before the first write. Release and language IDs are checked with regexes before use in Firestore paths (`ContentPackageMapper.kt`, `ContentSyncCoordinator.kt:65-104`).
- **Firestore rules:** read-only access to the content catalogue and default-deny for everything else (`firestore.rules`).
- **Secrets hygiene:** `google-services.json` and `local.properties` are gitignored and were never committed (checked with `git log --diff-filter=A`). The admin scripts read credentials only from the environment.
- **Room:** explicit migrations, exported schemas v1–v4, no `fallbackToDestructiveMigration`, and an atomic, idempotent mock-exam archive.
- **Process death:** Navigation3 keys are `@Serializable`, and the back stack and study position were restored after `am kill` on the emulator. The mock-exam deadline is persisted.
- **Compose:** `collectAsStateWithLifecycle` at routes, `selectableGroup`/`Role.RadioButton` on answers, stable lazy keys, `AutoMirrored` icons, `supportsRtl`, and per-app locales.
- **Privacy (at audit time):** no analytics or crash SDKs, and the policy at `website/privacy` matched app behaviour.
  - **Update (2026-11-05, after #29): NEEDS VERIFICATION.** Anonymous Firebase Authentication now creates a persistent Firebase user ID. The policy on `main` says the app "requires no user account" (`website/privacy/en/index.html:30`) and does not mention Firebase Authentication.
  - Re-check the privacy policy and the Play Data safety declaration. This is a product and legal decision; it was not re-audited here.
