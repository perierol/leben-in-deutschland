package com.nextdaymobilestudio.lebenindeutschland.feature.mockexam

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nextdaymobilestudio.lebenindeutschland.feature.mockexam.data.MockExamPreferencesRepository
import com.nextdaymobilestudio.lebenindeutschland.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MockExamViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()
    private val contentRepository = FakeMockExamContentRepository()
    private val preferencesRepository = FakeMockExamPreferencesRepository()
    private var now = START_EPOCH_MILLIS

    @Test
    fun startExamShowsFirstQuestionWithFullDuration() = runViewModelTest {
        val viewModel = createViewModel()
        assertEquals(MockExamUiState.Setup, viewModel.uiState.value)

        viewModel.startExam()

        val ready = viewModel.uiState.value as MockExamUiState.Ready
        assertEquals(0, ready.currentIndex)
        assertEquals(MockExamCoordinator.QUESTION_COUNT, ready.totalCount)
        assertEquals(0, ready.answeredCount)
        assertEquals(EXAM_DURATION_SECONDS, ready.remainingSeconds)
    }

    @Test
    fun timerTickUpdatesRemainingSeconds() = runViewModelTest {
        val viewModel = createViewModel()
        viewModel.startExam()

        now += 61_000L
        tick()

        val ready = viewModel.uiState.value as MockExamUiState.Ready
        assertEquals(EXAM_DURATION_SECONDS - 61, ready.remainingSeconds)
    }

    @Test
    fun timerExpiryAutoSubmitsWithTimeoutFlag() = runViewModelTest {
        val viewModel = createViewModel()
        viewModel.startExam()

        now += MockExamCoordinator.EXAM_DURATION_MILLIS
        tick()

        val result = viewModel.uiState.value as MockExamUiState.Result
        assertTrue(result.submittedByTimeout)
        assertEquals(MockExamCoordinator.QUESTION_COUNT, result.unansweredCount)
        assertEquals(1, contentRepository.mockExamResults.size)
        val saved = requireNotNull(preferencesRepository.saved.value)
        assertTrue(saved.isComplete)
        assertTrue(saved.submittedByTimeout)
    }

    @Test
    fun restoreAfterDeadlineSubmitsByTimeout() = runViewModelTest {
        coordinator().start(LANGUAGE_CODE, STATE_CODE)
        now += MockExamCoordinator.EXAM_DURATION_MILLIS + 1_000L

        val viewModel = createViewModel()

        val result = viewModel.uiState.value as MockExamUiState.Result
        assertTrue(result.submittedByTimeout)
        assertEquals(1, contentRepository.mockExamResults.size)
    }

    @Test
    fun restoreBeforeDeadlineResumesSavedQuestionAndRemainingTime() = runViewModelTest {
        val coordinator = coordinator()
        val started = coordinator.start(LANGUAGE_CODE, STATE_CODE)
        coordinator.moveTo(started, index = 5)
        now += 10 * 60_000L

        val viewModel = createViewModel()

        val ready = viewModel.uiState.value as MockExamUiState.Ready
        assertEquals(5, ready.currentIndex)
        assertEquals(EXAM_DURATION_SECONDS - 10 * 60, ready.remainingSeconds)
    }

    @Test
    fun answerSelectionIsIgnoredWhileThePreviousAnswerIsSaving() = runViewModelTest {
        val gatedPreferences = GatedMockExamPreferencesRepository(preferencesRepository)
        val viewModel = createViewModel(preferences = gatedPreferences)
        viewModel.startExam()
        val questionId = (viewModel.uiState.value as MockExamUiState.Ready).question.id

        gatedPreferences.holdNextSave()
        viewModel.selectAnswer("A")
        val saving = viewModel.uiState.value as MockExamUiState.Ready
        assertTrue(saving.isSaving)

        viewModel.selectAnswer("B")
        gatedPreferences.releaseSave()
        runCurrent()

        val ready = viewModel.uiState.value as MockExamUiState.Ready
        assertEquals("A", ready.selectedAnswerId)
        assertEquals(1, ready.answeredCount)
        assertFalse(ready.isSaving)
        assertEquals(
            mapOf(questionId to "A"),
            requireNotNull(preferencesRepository.saved.value).selectedAnswers,
        )
    }

    // F-04: an answer tapped before the deadline whose save is still running when the
    // timer expires must be part of the graded result.
    @Test
    fun answerTappedAtDeadlineWithPendingSave_isCountedInResult() = runViewModelTest {
        val gatedPreferences = GatedMockExamPreferencesRepository(preferencesRepository)
        val viewModel = createViewModel(preferences = gatedPreferences)
        viewModel.startExam()
        val questionId = (viewModel.uiState.value as MockExamUiState.Ready).question.id

        gatedPreferences.holdNextSave()
        viewModel.selectAnswer("A")
        now += MockExamCoordinator.EXAM_DURATION_MILLIS
        tick()
        gatedPreferences.releaseSave()
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue("Expected Result but was $state", state is MockExamUiState.Result)
        val result = state as MockExamUiState.Result
        assertTrue(result.submittedByTimeout)
        assertEquals(1, result.correctCount)
        assertEquals(MockExamCoordinator.QUESTION_COUNT - 1, result.unansweredCount)
        val archived = contentRepository.mockExamResults.single().summary
        assertEquals(1, archived.correctCount)
        assertEquals(MockExamCoordinator.QUESTION_COUNT - 1, archived.unansweredCount)
        assertEquals(
            mapOf(questionId to "A"),
            requireNotNull(preferencesRepository.saved.value).selectedAnswers,
        )
    }

    // F-04 guard: resolving a pending save at the deadline must submit exactly once.
    // Counts calls, because the attempt-ID dedupe would hide a second submit in history.
    @Test
    fun pendingSaveAtDeadline_submitsExactlyOnce() = runViewModelTest {
        val gatedPreferences = GatedMockExamPreferencesRepository(preferencesRepository)
        val viewModel = createViewModel(preferences = gatedPreferences)
        viewModel.startExam()

        gatedPreferences.holdNextSave()
        viewModel.selectAnswer("A")
        now += MockExamCoordinator.EXAM_DURATION_MILLIS
        tick()
        gatedPreferences.releaseSave()
        runCurrent()
        repeat(3) { tick() }

        assertTrue(viewModel.uiState.value is MockExamUiState.Result)
        assertEquals(1, contentRepository.recordCalls)
        assertEquals(1, preferencesRepository.completedSaves)
    }

    // F-04 guard: while the timeout submit is running, answer and navigation taps must
    // not save an incomplete snapshot over the completed one.
    @Test
    fun timeoutSubmitInFlight_ignoresAnswerAndNavigationTaps() = runViewModelTest {
        val gatedPreferences = GatedMockExamPreferencesRepository(preferencesRepository)
        val viewModel = createViewModel(preferences = gatedPreferences)
        viewModel.startExam()

        gatedPreferences.holdNextSave()
        now += MockExamCoordinator.EXAM_DURATION_MILLIS
        tick()
        // The timeout submit is now held while saving the completed snapshot.
        assertTrue(viewModel.uiState.value is MockExamUiState.Ready)
        viewModel.selectAnswer("A")
        viewModel.nextQuestion()
        gatedPreferences.releaseSave()
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue("Expected Result but was $state", state is MockExamUiState.Result)
        assertEquals(MockExamCoordinator.QUESTION_COUNT, (state as MockExamUiState.Result).unansweredCount)
        val saved = requireNotNull(preferencesRepository.saved.value)
        assertTrue("Completed snapshot was overwritten", saved.isComplete)
        assertTrue(saved.selectedAnswers.isEmpty())
        assertEquals(1, contentRepository.recordCalls)
    }

    // F-04 guard: a pending answer save that fails at the deadline shows Error without
    // submitting, and retry then submits by timeout on restore.
    @Test
    fun pendingSaveFailsAtDeadline_showsErrorThenRetrySubmitsByTimeout() = runViewModelTest {
        val gatedPreferences = GatedMockExamPreferencesRepository(preferencesRepository)
        val viewModel = createViewModel(preferences = gatedPreferences)
        viewModel.startExam()

        gatedPreferences.holdNextSave()
        viewModel.selectAnswer("A")
        now += MockExamCoordinator.EXAM_DURATION_MILLIS
        tick()
        gatedPreferences.failSave(IllegalStateException("Simulated save failure"))
        runCurrent()

        assertEquals(MockExamUiState.Error, viewModel.uiState.value)
        assertEquals(0, contentRepository.recordCalls)
        assertFalse(requireNotNull(preferencesRepository.saved.value).isComplete)

        viewModel.retry()

        val state = viewModel.uiState.value
        assertTrue("Expected Result but was $state", state is MockExamUiState.Result)
        assertTrue((state as MockExamUiState.Result).submittedByTimeout)
        assertEquals(1, contentRepository.recordCalls)
    }

    // F-04 guard: retry() after a failed manual submit must clear the submission lock,
    // or the learner could no longer answer or submit.
    @Test
    fun submitFailureThenRetry_acceptsAnswersAgain() = runViewModelTest {
        val viewModel = createViewModel()
        viewModel.startExam()
        viewModel.selectAnswer("A")
        preferencesRepository.failNextCompletedSave = true
        viewModel.confirmSubmit()
        assertEquals(MockExamUiState.Error, viewModel.uiState.value)

        viewModel.retry()
        assertEquals("A", (viewModel.uiState.value as MockExamUiState.Ready).selectedAnswerId)
        viewModel.selectAnswer("B")
        assertEquals("B", (viewModel.uiState.value as MockExamUiState.Ready).selectedAnswerId)
        viewModel.confirmSubmit()

        val state = viewModel.uiState.value
        assertTrue("Expected Result but was $state", state is MockExamUiState.Result)
        assertFalse((state as MockExamUiState.Result).submittedByTimeout)
        assertEquals(1, contentRepository.recordCalls)
        assertEquals(1, contentRepository.mockExamResults.size)
    }

    // F-04 guard: startNewExam() after a result must clear the submission lock.
    @Test
    fun startNewExamAfterResult_acceptsAnswers() = runViewModelTest {
        val viewModel = createViewModel()
        viewModel.startExam()
        viewModel.confirmSubmit()
        assertTrue(viewModel.uiState.value is MockExamUiState.Result)

        viewModel.startNewExam()
        viewModel.startExam()
        viewModel.selectAnswer("A")

        val ready = viewModel.uiState.value as MockExamUiState.Ready
        assertEquals("A", ready.selectedAnswerId)
        assertEquals(1, ready.answeredCount)
    }

    // F-05: after a crash between archiving and saving the completed snapshot, the next
    // launch must show the result that history stores, not a resumable exam.
    @Test
    fun restartAfterArchiveCrash_showsResultMatchingHistory() = runViewModelTest {
        val firstViewModel = createViewModel()
        firstViewModel.startExam()
        firstViewModel.selectAnswer("A")
        contentRepository.failNextRecord = true
        firstViewModel.confirmSubmit()
        assertEquals(MockExamUiState.Error, firstViewModel.uiState.value)

        // Simulates process death: the next ViewModel restores from preferences.
        viewModelStore.clear()
        val restoredViewModel = createViewModel()

        val state = restoredViewModel.uiState.value
        assertTrue("Expected Result but was $state", state is MockExamUiState.Result)
        val result = state as MockExamUiState.Result
        val archived = contentRepository.mockExamResults.single().summary
        assertEquals(1, result.correctCount)
        assertEquals(archived.correctCount, result.correctCount)
        assertEquals(archived.unansweredCount, result.unansweredCount)
    }

    private fun runViewModelTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            // Cancels viewModelScope, including the exam timer loop.
            viewModelStore.clear()
        }
    }

    private fun TestScope.tick() {
        advanceTimeBy(TIMER_TICK_MILLIS)
        runCurrent()
    }

    private fun coordinator(
        preferences: MockExamPreferencesRepository = preferencesRepository,
    ): MockExamCoordinator = MockExamCoordinator(
        localContentRepository = contentRepository,
        mockExamPreferencesRepository = preferences,
        currentTimeMillis = { now },
        shuffle = { values -> values.reversed() },
    )

    private fun createViewModel(
        preferences: MockExamPreferencesRepository = preferencesRepository,
    ): MockExamViewModel = ViewModelProvider.create(
        store = viewModelStore,
        factory = viewModelFactory {
            initializer {
                MockExamViewModel(
                    languageCode = LANGUAGE_CODE,
                    stateCode = STATE_CODE,
                    coordinator = coordinator(preferences),
                    currentTimeMillis = { now },
                )
            }
        },
    )[MockExamViewModel::class]
}

private class GatedMockExamPreferencesRepository(
    private val delegate: MockExamPreferencesRepository,
) : MockExamPreferencesRepository {
    private var gate: CompletableDeferred<Unit>? = null

    override val exam: Flow<MockExamSnapshot?> = delegate.exam

    fun holdNextSave() {
        gate = CompletableDeferred()
    }

    fun releaseSave() {
        gate?.complete(Unit)
    }

    fun failSave(cause: Throwable) {
        gate?.completeExceptionally(cause)
    }

    override suspend fun getExam(): MockExamSnapshot? = delegate.getExam()

    override suspend fun saveExam(exam: MockExamSnapshot) {
        try {
            gate?.await()
        } finally {
            // One-shot, also when the held save fails or is cancelled.
            gate = null
        }
        delegate.saveExam(exam)
    }

    override suspend fun clearExam() = delegate.clearExam()
}

private const val LANGUAGE_CODE = "tr"
private const val STATE_CODE = "he"
private const val START_EPOCH_MILLIS = 100_000L
private const val TIMER_TICK_MILLIS = 1_000L
private const val EXAM_DURATION_SECONDS = MockExamCoordinator.EXAM_DURATION_MILLIS / 1_000L
