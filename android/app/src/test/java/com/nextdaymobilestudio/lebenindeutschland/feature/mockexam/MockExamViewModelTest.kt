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

    override suspend fun getExam(): MockExamSnapshot? = delegate.getExam()

    override suspend fun saveExam(exam: MockExamSnapshot) {
        gate?.await()
        gate = null
        delegate.saveExam(exam)
    }

    override suspend fun clearExam() = delegate.clearExam()
}

private const val LANGUAGE_CODE = "tr"
private const val STATE_CODE = "he"
private const val START_EPOCH_MILLIS = 100_000L
private const val TIMER_TICK_MILLIS = 1_000L
private const val EXAM_DURATION_SECONDS = MockExamCoordinator.EXAM_DURATION_MILLIS / 1_000L
