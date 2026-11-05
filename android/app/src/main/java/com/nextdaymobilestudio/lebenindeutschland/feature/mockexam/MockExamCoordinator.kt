package com.nextdaymobilestudio.lebenindeutschland.feature.mockexam

import com.nextdaymobilestudio.lebenindeutschland.core.database.model.MockExamHistoryItem
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.MockExamQuestionResult
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.MockExamResultRecord
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyQuestion
import com.nextdaymobilestudio.lebenindeutschland.core.database.repository.LocalContentRepository
import com.nextdaymobilestudio.lebenindeutschland.feature.mockexam.data.MockExamPreferencesRepository
import java.util.Locale
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class MockExamCoordinator(
    private val localContentRepository: LocalContentRepository,
    private val mockExamPreferencesRepository: MockExamPreferencesRepository,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val shuffle: (List<String>) -> List<String> = { values -> values.shuffled() },
) {
    suspend fun restore(
        languageCode: String,
        stateCode: String?,
    ): MockExamSnapshot? {
        val savedExam = mockExamPreferencesRepository.getExam() ?: return null
        val activeRelease = localContentRepository.getActiveRelease()
        val normalizedStateCode = stateCode?.uppercase(Locale.ROOT)
        val matchesCurrentSelection = activeRelease?.releaseId == savedExam.releaseId &&
            savedExam.languageCode == languageCode &&
            savedExam.stateCode == normalizedStateCode
        if (!matchesCurrentSelection || savedExam.questionIds.size != QUESTION_COUNT) {
            mockExamPreferencesRepository.clearExam()
            return null
        }
        val restoredExam = savedExam.copy(
            currentIndex = savedExam.currentIndex.coerceIn(savedExam.questionIds.indices),
        )
        if (restoredExam.isComplete) {
            archiveCompletedExam(
                exam = restoredExam,
                updateStudyProgress = false,
            )
        }
        return restoredExam
    }

    suspend fun start(
        languageCode: String,
        stateCode: String?,
    ): MockExamSnapshot {
        val release = requireNotNull(localContentRepository.getActiveRelease()) {
            "An active content release is required"
        }
        val normalizedStateCode = requireNotNull(stateCode?.uppercase(Locale.ROOT)) {
            "A federal state is required for a mock exam"
        }
        val generalQuestionIds = localContentRepository.getGeneralQuestionIds(
            releaseId = release.releaseId,
            languageCode = languageCode,
        ).distinct()
        val stateQuestionIds = localContentRepository.getStateQuestionIds(
            releaseId = release.releaseId,
            languageCode = languageCode,
            stateCode = normalizedStateCode,
        ).distinct()
        require(generalQuestionIds.size >= GENERAL_QUESTION_COUNT) {
            "At least $GENERAL_QUESTION_COUNT general questions are required"
        }
        require(stateQuestionIds.size >= STATE_QUESTION_COUNT) {
            "At least $STATE_QUESTION_COUNT state questions are required"
        }

        val selectedQuestionIds = shuffle(
            shuffle(generalQuestionIds).take(GENERAL_QUESTION_COUNT) +
                shuffle(stateQuestionIds).take(STATE_QUESTION_COUNT),
        )
        require(selectedQuestionIds.distinct().size == QUESTION_COUNT) {
            "A mock exam must contain $QUESTION_COUNT unique questions"
        }

        val startedAt = currentTimeMillis()
        return MockExamSnapshot(
            releaseId = release.releaseId,
            languageCode = languageCode,
            stateCode = normalizedStateCode,
            questionIds = selectedQuestionIds,
            startedAtEpochMillis = startedAt,
            deadlineEpochMillis = startedAt + EXAM_DURATION_MILLIS,
        ).also { exam ->
            mockExamPreferencesRepository.saveExam(exam)
        }
    }

    suspend fun getQuestion(
        exam: MockExamSnapshot,
        index: Int,
    ): StudyQuestion {
        val questionId = exam.questionIds[index]
        return requireNotNull(
            localContentRepository.getStudyQuestion(
                releaseId = exam.releaseId,
                questionId = questionId,
                languageCode = exam.languageCode,
            ),
        ) { "Missing installed question: $questionId" }
    }

    suspend fun selectAnswer(
        exam: MockExamSnapshot,
        question: StudyQuestion,
        answerId: String,
    ): MockExamSnapshot {
        require(!exam.isComplete)
        require(question.id in exam.questionIds)
        require(question.answers.any { answer -> answer.id == answerId })
        return exam.copy(
            selectedAnswers = exam.selectedAnswers + (question.id to answerId),
        ).also { updated ->
            mockExamPreferencesRepository.saveExam(updated)
        }
    }

    suspend fun moveTo(
        exam: MockExamSnapshot,
        index: Int,
    ): MockExamSnapshot {
        require(!exam.isComplete)
        return exam.copy(
            currentIndex = index.coerceIn(exam.questionIds.indices),
        ).also { updated ->
            mockExamPreferencesRepository.saveExam(updated)
        }
    }

    suspend fun submit(
        exam: MockExamSnapshot,
        submittedByTimeout: Boolean,
    ): MockExamSnapshot {
        if (exam.isComplete) return exam
        val questionsById = exam.questionIds.associateWith { questionId ->
            requireNotNull(
                localContentRepository.getStudyQuestion(
                    releaseId = exam.releaseId,
                    questionId = questionId,
                    languageCode = exam.languageCode,
                ),
            ) { "Missing installed question: $questionId" }
        }
        val correctCount = exam.selectedAnswers.count { (questionId, selectedAnswerId) ->
            questionsById.getValue(questionId).correctAnswerId == selectedAnswerId
        }
        val submittedAtEpochMillis = currentTimeMillis()
        val completed = exam.copy(
            submittedAtEpochMillis = submittedAtEpochMillis,
            correctCount = correctCount,
            submittedByTimeout = submittedByTimeout,
        )
        // Persist the completed snapshot before archiving. If the process dies in between,
        // restore() sees a completed exam and archives it idempotently. NonCancellable keeps
        // a cancelled caller (e.g. leaving the screen) from splitting the two writes.
        withContext(NonCancellable) {
            mockExamPreferencesRepository.saveExam(completed)
            archiveCompletedExam(
                exam = completed,
                questionsById = questionsById,
                updateStudyProgress = true,
            )
        }
        return completed
    }

    private suspend fun archiveCompletedExam(
        exam: MockExamSnapshot,
        questionsById: Map<String, StudyQuestion>? = null,
        updateStudyProgress: Boolean,
    ) {
        require(exam.isComplete)
        val submittedAtEpochMillis = requireNotNull(exam.submittedAtEpochMillis)
        val resolvedQuestions = questionsById ?: exam.questionIds.associateWith { questionId ->
            requireNotNull(
                localContentRepository.getStudyQuestion(
                    releaseId = exam.releaseId,
                    questionId = questionId,
                    languageCode = exam.languageCode,
                ),
            ) { "Missing installed question: $questionId" }
        }
        val questionResults = exam.questionIds.mapIndexed { index, questionId ->
            MockExamQuestionResult(
                questionId = questionId,
                position = index,
                selectedAnswerId = exam.selectedAnswers[questionId],
                correctAnswerId = resolvedQuestions.getValue(questionId).correctAnswerId,
            )
        }
        val correctCount = questionResults.count(MockExamQuestionResult::isCorrect)
        val unansweredCount = questionResults.count { result -> !result.isAnswered }
        val wrongCount = questionResults.count { result -> result.isAnswered && !result.isCorrect }
        localContentRepository.recordMockExamResult(
            MockExamResultRecord(
                summary = MockExamHistoryItem(
                    attemptId = exam.attemptId,
                    releaseId = exam.releaseId,
                    languageCode = exam.languageCode,
                    stateCode = exam.stateCode,
                    startedAtEpochMillis = exam.startedAtEpochMillis,
                    submittedAtEpochMillis = submittedAtEpochMillis,
                    deadlineEpochMillis = exam.deadlineEpochMillis,
                    correctCount = correctCount,
                    wrongCount = wrongCount,
                    unansweredCount = unansweredCount,
                    totalCount = exam.questionIds.size,
                    submittedByTimeout = exam.submittedByTimeout,
                ),
                questionResults = questionResults,
            ),
            updateStudyProgress = updateStudyProgress,
        )
    }

    suspend fun clear() {
        mockExamPreferencesRepository.clearExam()
    }

    companion object {
        const val GENERAL_QUESTION_COUNT = 30
        const val STATE_QUESTION_COUNT = 3
        const val QUESTION_COUNT = GENERAL_QUESTION_COUNT + STATE_QUESTION_COUNT
        const val EXAM_DURATION_MINUTES = 60
        const val EXAM_DURATION_MILLIS = EXAM_DURATION_MINUTES * 60L * 1_000L
    }
}
