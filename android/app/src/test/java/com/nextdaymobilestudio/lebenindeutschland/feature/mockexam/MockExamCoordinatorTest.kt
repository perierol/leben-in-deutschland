package com.nextdaymobilestudio.lebenindeutschland.feature.mockexam

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MockExamCoordinatorTest {
    @Test
    fun startCreatesThirtyGeneralAndThreeStateQuestions() = runBlocking {
        val localRepository = FakeMockExamContentRepository()
        val preferencesRepository = FakeMockExamPreferencesRepository()
        val coordinator = coordinator(localRepository, preferencesRepository)

        val exam = coordinator.start("tr", "he")

        assertEquals(33, exam.questionIds.size)
        assertEquals(33, exam.questionIds.distinct().size)
        assertEquals(30, exam.questionIds.count { it.startsWith("general_") })
        assertEquals(3, exam.questionIds.count { it.startsWith("state_he_") })
        assertEquals(
            listOf("state_he_008", "state_he_009", "state_he_010"),
            exam.questionIds.take(3),
        )
        assertEquals("general_011", exam.questionIds[3])
        assertEquals("HE", exam.stateCode)
        assertEquals(3_700_000L, exam.deadlineEpochMillis)
        assertEquals(exam, preferencesRepository.saved.value)
    }

    @Test
    fun oneResultAppliesBothOfficialPassThresholds() {
        val lifeInGermanyResult = result(correctCount = 15)
        val naturalizationResult = result(correctCount = 17)

        assertTrue(lifeInGermanyResult.lifeInGermanyPassed)
        assertFalse(lifeInGermanyResult.naturalizationPassed)
        assertTrue(naturalizationResult.lifeInGermanyPassed)
        assertTrue(naturalizationResult.naturalizationPassed)
    }

    @Test
    fun selectedAnswerAndCurrentQuestionArePersisted() = runBlocking {
        val localRepository = FakeMockExamContentRepository()
        val preferencesRepository = FakeMockExamPreferencesRepository()
        val coordinator = coordinator(localRepository, preferencesRepository)
        val exam = coordinator.start("tr", "he")
        val question = coordinator.getQuestion(exam, 0)

        val answered = coordinator.selectAnswer(exam, question, "A")
        val moved = coordinator.moveTo(answered, 6)

        assertEquals("A", moved.selectedAnswers[question.id])
        assertEquals(6, moved.currentIndex)
        assertEquals(moved, preferencesRepository.saved.value)
        assertTrue(localRepository.studyAnswerWrites.isEmpty())
    }

    @Test
    fun restoreReturnsMatchingExamAndClearsStaleSelection() = runBlocking {
        val localRepository = FakeMockExamContentRepository()
        val preferencesRepository = FakeMockExamPreferencesRepository()
        val coordinator = coordinator(localRepository, preferencesRepository)
        val exam = coordinator.start("tr", "he")

        assertEquals(exam, coordinator.restore("tr", "he"))
        assertNull(coordinator.restore("en", "he"))
        assertNull(preferencesRepository.saved.value)
    }

    @Test
    fun submitRecordsAnsweredQuestionsAndLeavesBlanksUnchanged() = runBlocking {
        val localRepository = FakeMockExamContentRepository()
        val preferencesRepository = FakeMockExamPreferencesRepository()
        val coordinator = coordinator(localRepository, preferencesRepository)
        var exam = coordinator.start("tr", "he")
        val first = coordinator.getQuestion(exam, 0)
        exam = coordinator.selectAnswer(exam, first, "A")
        val second = coordinator.getQuestion(exam, 1)
        exam = coordinator.selectAnswer(exam, second, "B")

        val completed = coordinator.submit(exam, submittedByTimeout = false)

        assertTrue(completed.isComplete)
        assertEquals(1, completed.correctCount)
        assertEquals(1, completed.wrongCount)
        assertEquals(31, completed.unansweredCount)
        assertFalse(completed.submittedByTimeout)
        assertEquals(2, localRepository.studyAnswerWrites.size)
        assertEquals(first.id, localRepository.studyAnswerWrites[0].questionId)
        assertEquals("A", localRepository.studyAnswerWrites[0].selectedAnswerId)
        assertTrue(localRepository.studyAnswerWrites[0].isCorrect)
        assertEquals(second.id, localRepository.studyAnswerWrites[1].questionId)
        assertEquals("B", localRepository.studyAnswerWrites[1].selectedAnswerId)
        assertFalse(localRepository.studyAnswerWrites[1].isCorrect)
        assertEquals(1, localRepository.mockExamResults.size)
        assertEquals(completed.attemptId, localRepository.mockExamResults.single().summary.attemptId)
        assertEquals(33, localRepository.mockExamResults.single().questionResults.size)

        assertEquals(completed, coordinator.submit(completed, submittedByTimeout = false))
        assertEquals(2, localRepository.studyAnswerWrites.size)
        assertEquals(1, localRepository.mockExamResults.size)
    }

    @Test
    fun restoreBackfillsLegacyCompletedExamWithoutCountingProgressTwice() = runBlocking {
        val localRepository = FakeMockExamContentRepository()
        val preferencesRepository = FakeMockExamPreferencesRepository()
        val coordinator = coordinator(localRepository, preferencesRepository)
        val exam = coordinator.start("tr", "he")
        val completed = exam.copy(
            selectedAnswers = mapOf(exam.questionIds.first() to "A"),
            submittedAtEpochMillis = 200_000L,
            correctCount = 1,
        )
        preferencesRepository.saveExam(completed)

        assertEquals(completed, coordinator.restore("tr", "he"))
        assertEquals(1, localRepository.mockExamResults.size)
        assertTrue(localRepository.studyAnswerWrites.isEmpty())

        assertEquals(completed, coordinator.restore("tr", "he"))
        assertEquals(1, localRepository.mockExamResults.size)
        assertTrue(localRepository.studyAnswerWrites.isEmpty())
    }

    @Test
    fun startRejectsCatalogWithoutThreeStateQuestions() {
        val localRepository = FakeMockExamContentRepository(
            stateIds = listOf("state_he_001", "state_he_002"),
        )
        val coordinator = coordinator(
            localRepository,
            FakeMockExamPreferencesRepository(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { coordinator.start("tr", "he") }
        }
    }

    private fun coordinator(
        localRepository: FakeMockExamContentRepository,
        preferencesRepository: FakeMockExamPreferencesRepository,
    ): MockExamCoordinator = MockExamCoordinator(
        localContentRepository = localRepository,
        mockExamPreferencesRepository = preferencesRepository,
        currentTimeMillis = { 100_000L },
        shuffle = { values -> values.reversed() },
    )
}

private fun result(correctCount: Int): MockExamUiState.Result = MockExamUiState.Result(
    correctCount = correctCount,
    wrongCount = 33 - correctCount,
    unansweredCount = 0,
    totalCount = 33,
    submittedByTimeout = false,
)
