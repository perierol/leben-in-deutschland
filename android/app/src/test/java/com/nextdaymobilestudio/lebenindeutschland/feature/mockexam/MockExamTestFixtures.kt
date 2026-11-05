package com.nextdaymobilestudio.lebenindeutschland.feature.mockexam

import com.nextdaymobilestudio.lebenindeutschland.core.database.entity.ContentPackageEntity
import com.nextdaymobilestudio.lebenindeutschland.core.database.entity.ContentReleaseEntity
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.ContentPackageSnapshot
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.MockExamResultRecord
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyAnswer
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyAnswerProgress
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyAnswerRecord
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyQuestion
import com.nextdaymobilestudio.lebenindeutschland.core.database.repository.LocalContentRepository
import com.nextdaymobilestudio.lebenindeutschland.feature.mockexam.data.MockExamPreferencesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeMockExamPreferencesRepository : MockExamPreferencesRepository {
    val saved = MutableStateFlow<MockExamSnapshot?>(null)
    override val exam: Flow<MockExamSnapshot?> = saved

    /** Simulates process death while the completed snapshot is being written. One-shot. */
    var failNextCompletedSave = false

    /** Successful saves of a completed snapshot; a double submit shows up here. */
    var completedSaves = 0
        private set

    override suspend fun getExam(): MockExamSnapshot? = saved.value

    override suspend fun saveExam(exam: MockExamSnapshot) {
        if (exam.isComplete && failNextCompletedSave) {
            failNextCompletedSave = false
            throw IllegalStateException("Simulated crash while saving the completed exam")
        }
        saved.value = exam
        if (exam.isComplete) completedSaves += 1
    }

    override suspend fun clearExam() {
        saved.value = null
    }
}

internal class FakeMockExamContentRepository(
    private val generalIds: List<String> = (1..40).map { index ->
        "general_${index.toString().padStart(3, '0')}"
    },
    private val stateIds: List<String> = (1..10).map { index ->
        "state_he_${index.toString().padStart(3, '0')}"
    },
) : LocalContentRepository {
    private val release = mockExamRelease()
    val studyAnswerWrites = mutableListOf<StudyAnswerRecord>()
    val mockExamResults = mutableListOf<MockExamResultRecord>()

    /** Simulates process death while the exam is being archived to history. One-shot. */
    var failNextRecord = false

    /** When set, the next archive waits for this before writing. One-shot. */
    var holdNextRecord: CompletableDeferred<Unit>? = null

    /**
     * Archive attempts, including ones the attempt-ID dedupe ignores, so a double
     * submit is visible even though history keeps one row.
     */
    var recordCalls = 0
        private set

    override fun observeActiveRelease(): Flow<ContentReleaseEntity?> =
        MutableStateFlow(release)

    override fun observeInstalledPackages(releaseId: String): Flow<List<ContentPackageEntity>> =
        MutableStateFlow(emptyList())

    override suspend fun getActiveRelease(): ContentReleaseEntity = release

    override suspend fun getPackage(
        releaseId: String,
        languageCode: String,
    ): ContentPackageEntity? = null

    override suspend fun replacePackage(snapshot: ContentPackageSnapshot) = Unit

    override suspend fun activateRelease(releaseId: String, requiredLanguageCode: String) = Unit

    override suspend fun deleteRelease(releaseId: String) = Unit

    override suspend fun getEligibleQuestionIds(
        releaseId: String,
        languageCode: String,
        stateCode: String?,
    ): List<String> = generalIds + if (stateCode == null) emptyList() else stateIds

    override suspend fun getGeneralQuestionIds(
        releaseId: String,
        languageCode: String,
    ): List<String> = generalIds

    override suspend fun getStateQuestionIds(
        releaseId: String,
        languageCode: String,
        stateCode: String,
    ): List<String> = stateIds

    override suspend fun getStudyQuestion(
        releaseId: String,
        questionId: String,
        languageCode: String,
    ): StudyQuestion = mockExamQuestion(questionId, languageCode)

    override suspend fun recordStudyAnswer(
        releaseId: String,
        languageCode: String,
        stateCode: String?,
        questionId: String,
        selectedAnswerId: String,
        isCorrect: Boolean,
        answeredAtEpochMillis: Long,
    ): StudyAnswerProgress {
        studyAnswerWrites += StudyAnswerRecord(
            questionId = questionId,
            selectedAnswerId = selectedAnswerId,
            isCorrect = isCorrect,
        )
        return StudyAnswerProgress(
            questionId = questionId,
            selectedAnswerId = selectedAnswerId,
            isCorrect = isCorrect,
            answeredAtEpochMillis = answeredAtEpochMillis,
            attemptCount = 1,
        )
    }

    override suspend fun recordMockExamResult(
        result: MockExamResultRecord,
        updateStudyProgress: Boolean,
    ): Boolean {
        recordCalls += 1
        holdNextRecord?.let { gate ->
            holdNextRecord = null
            gate.await()
        }
        if (failNextRecord) {
            failNextRecord = false
            throw IllegalStateException("Simulated crash while archiving the exam")
        }
        if (mockExamResults.any { saved -> saved.summary.attemptId == result.summary.attemptId }) {
            return false
        }
        mockExamResults += result
        if (updateStudyProgress) {
            super<LocalContentRepository>.recordMockExamResult(
                result = result,
                updateStudyProgress = true,
            )
        }
        return true
    }
}

internal fun mockExamRelease(): ContentReleaseEntity = ContentReleaseEntity(
    releaseId = MOCK_EXAM_RELEASE_ID,
    schemaVersion = 3,
    datasetVersion = "2026-10",
    packageStrategy = "SELF_CONTAINED_LANGUAGE_PACK",
    status = "READY",
    createdAt = "2026-10-26T00:00:00Z",
    sourceCatalogSha256 = "a".repeat(64),
    catalogSha256 = "b".repeat(64),
    questionCount = 460,
    chunkSize = 10,
    languageCount = 21,
    qualityIssueCount = 0,
    installedAtEpochMillis = 1L,
    isActive = true,
)

internal fun mockExamQuestion(
    questionId: String,
    languageCode: String,
): StudyQuestion = StudyQuestion(
    releaseId = MOCK_EXAM_RELEASE_ID,
    id = questionId,
    officialNumber = 1,
    scope = if (questionId.startsWith("general_")) "GENERAL" else "STATE",
    stateCode = if (questionId.startsWith("general_")) null else "HE",
    category = null,
    correctAnswerId = "A",
    germanQuestionText = "Was ist richtig?",
    localizedQuestionText = "Hangisi doğrudur?",
    explanation = "Erklärung",
    languageCode = languageCode,
    questionImage = null,
    answers = listOf(
        StudyAnswer("A", 0, "Antwort A", "Cevap A", null),
        StudyAnswer("B", 1, "Antwort B", "Cevap B", null),
        StudyAnswer("C", 2, "Antwort C", "Cevap C", null),
        StudyAnswer("D", 3, "Antwort D", "Cevap D", null),
    ),
    explanationStatus = "AI_GENERATED_NEEDS_REVIEW",
    reviewed = false,
)

internal const val MOCK_EXAM_RELEASE_ID = "release-mock-exam"
