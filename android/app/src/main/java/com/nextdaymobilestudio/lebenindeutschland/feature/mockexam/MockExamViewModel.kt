package com.nextdaymobilestudio.lebenindeutschland.feature.mockexam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nextdaymobilestudio.lebenindeutschland.core.database.repository.LocalContentRepository
import com.nextdaymobilestudio.lebenindeutschland.feature.mockexam.data.MockExamPreferencesRepository
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MockExamViewModel(
    private val languageCode: String,
    private val stateCode: String?,
    private val coordinator: MockExamCoordinator,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _uiState = MutableStateFlow<MockExamUiState>(MockExamUiState.Loading)
    val uiState: StateFlow<MockExamUiState> = _uiState.asStateFlow()

    private var exam: MockExamSnapshot? = null
    private var actionJob: Job? = null
    private var timerJob: Job? = null

    // Set once a manual or timeout submission starts, so no answer or navigation
    // save can overwrite the completed snapshot. Cleared by retry() and startNewExam().
    private var isSubmitting = false

    init {
        retry()
    }

    fun retry() {
        actionJob?.cancel()
        timerJob?.cancel()
        isSubmitting = false
        actionJob = viewModelScope.launch {
            _uiState.value = MockExamUiState.Loading
            runAction {
                val restoredExam = coordinator.restore(languageCode, stateCode)
                exam = restoredExam
                when {
                    restoredExam == null -> _uiState.value = MockExamUiState.Setup
                    restoredExam.isComplete -> showResult(restoredExam)
                    restoredExam.deadlineEpochMillis <= currentTimeMillis() -> {
                        submit(restoredExam, submittedByTimeout = true)
                    }
                    else -> showQuestion(restoredExam)
                }
            }
        }
    }

    fun startExam() {
        if (_uiState.value !is MockExamUiState.Setup) return
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
            _uiState.value = MockExamUiState.Loading
            runAction {
                val startedExam = coordinator.start(languageCode, stateCode)
                exam = startedExam
                showQuestion(startedExam)
            }
        }
    }

    fun selectAnswer(answerId: String) {
        val current = _uiState.value as? MockExamUiState.Ready ?: return
        val loadedExam = exam ?: return
        if (current.isSaving || isSubmitting) return
        actionJob = viewModelScope.launch {
            _uiState.value = current.copy(
                selectedAnswerId = answerId,
                answeredCount = if (current.selectedAnswerId == null) {
                    current.answeredCount + 1
                } else {
                    current.answeredCount
                },
                isSaving = true,
            )
            runAction {
                val updatedExam = coordinator.selectAnswer(
                    exam = loadedExam,
                    question = current.question,
                    answerId = answerId,
                )
                exam = updatedExam
                val latest = _uiState.value
                if (latest is MockExamUiState.Ready) {
                    _uiState.value = latest.copy(isSaving = false)
                }
            }
        }
    }

    fun previousQuestion() = moveBy(-1)

    fun nextQuestion() = moveBy(1)

    fun requestSubmit() {
        val current = _uiState.value as? MockExamUiState.Ready ?: return
        if (!current.isSaving) {
            _uiState.value = current.copy(showSubmitConfirmation = true)
        }
    }

    fun cancelSubmit() {
        val current = _uiState.value as? MockExamUiState.Ready ?: return
        _uiState.value = current.copy(showSubmitConfirmation = false)
    }

    fun confirmSubmit() {
        val current = _uiState.value as? MockExamUiState.Ready ?: return
        val loadedExam = exam ?: return
        if (current.isSaving || isSubmitting) return
        isSubmitting = true
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
            _uiState.value = current.copy(
                showSubmitConfirmation = false,
                isSaving = true,
            )
            runAction { submit(loadedExam, submittedByTimeout = false) }
        }
    }

    fun startNewExam() {
        actionJob?.cancel()
        timerJob?.cancel()
        isSubmitting = false
        actionJob = viewModelScope.launch {
            _uiState.value = MockExamUiState.Loading
            runAction {
                coordinator.clear()
                exam = null
                _uiState.value = MockExamUiState.Setup
            }
        }
    }

    private fun moveBy(offset: Int) {
        val current = _uiState.value as? MockExamUiState.Ready ?: return
        val loadedExam = exam ?: return
        if (current.isSaving || isSubmitting) return
        val targetIndex = (current.currentIndex + offset)
            .coerceIn(0, current.totalCount - 1)
        if (targetIndex == current.currentIndex) return
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
            _uiState.value = current.copy(isSaving = true)
            runAction {
                val updatedExam = coordinator.moveTo(loadedExam, targetIndex)
                exam = updatedExam
                showQuestion(updatedExam, startTimer = false)
            }
        }
    }

    private suspend fun showQuestion(
        loadedExam: MockExamSnapshot,
        startTimer: Boolean = true,
    ) {
        val question = coordinator.getQuestion(loadedExam, loadedExam.currentIndex)
        _uiState.value = MockExamUiState.Ready(
            question = question,
            currentIndex = loadedExam.currentIndex,
            totalCount = loadedExam.questionIds.size,
            answeredCount = loadedExam.answeredCount,
            selectedAnswerId = loadedExam.selectedAnswers[question.id],
            remainingSeconds = remainingSeconds(loadedExam),
        )
        if (startTimer) startTimer()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(TIMER_TICK_MILLIS)
                val loadedExam = exam ?: return@launch
                if (loadedExam.isComplete) return@launch
                val remainingSeconds = remainingSeconds(loadedExam)
                if (remainingSeconds <= 0) {
                    if (isSubmitting) return@launch
                    isSubmitting = true
                    // Let an answer or navigation save that started before the deadline
                    // finish, then submit the latest snapshot instead of a stale copy.
                    val pendingAction = actionJob
                    actionJob = viewModelScope.launch {
                        pendingAction?.join()
                        val latestExam = exam ?: return@launch
                        if (latestExam.isComplete || _uiState.value !is MockExamUiState.Ready) {
                            return@launch
                        }
                        runAction { submit(latestExam, submittedByTimeout = true) }
                    }
                    return@launch
                }
                val current = _uiState.value as? MockExamUiState.Ready ?: continue
                _uiState.value = current.copy(remainingSeconds = remainingSeconds)
            }
        }
    }

    private suspend fun submit(
        loadedExam: MockExamSnapshot,
        submittedByTimeout: Boolean,
    ) {
        timerJob?.cancel()
        val completedExam = coordinator.submit(loadedExam, submittedByTimeout)
        exam = completedExam
        showResult(completedExam)
    }

    private fun showResult(completedExam: MockExamSnapshot) {
        _uiState.value = MockExamUiState.Result(
            correctCount = completedExam.correctCount ?: 0,
            wrongCount = completedExam.wrongCount,
            unansweredCount = completedExam.unansweredCount,
            totalCount = completedExam.questionIds.size,
            submittedByTimeout = completedExam.submittedByTimeout,
        )
    }

    private fun remainingSeconds(loadedExam: MockExamSnapshot): Long = ceil(
        (loadedExam.deadlineEpochMillis - currentTimeMillis()).coerceAtLeast(0L) / 1_000.0,
    ).toLong()

    private suspend fun runAction(action: suspend () -> Unit) {
        try {
            action()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            timerJob?.cancel()
            _uiState.value = MockExamUiState.Error
        }
    }

    override fun onCleared() {
        timerJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TIMER_TICK_MILLIS = 1_000L

        fun factory(
            languageCode: String,
            stateCode: String?,
            localContentRepository: LocalContentRepository,
            mockExamPreferencesRepository: MockExamPreferencesRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MockExamViewModel(
                    languageCode = languageCode,
                    stateCode = stateCode,
                    coordinator = MockExamCoordinator(
                        localContentRepository = localContentRepository,
                        mockExamPreferencesRepository = mockExamPreferencesRepository,
                    ),
                ) as T
        }
    }
}
