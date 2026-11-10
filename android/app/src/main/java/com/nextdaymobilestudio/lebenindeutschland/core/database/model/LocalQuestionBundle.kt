package com.nextdaymobilestudio.lebenindeutschland.core.database.model

import com.nextdaymobilestudio.lebenindeutschland.core.database.entity.AnswerEntity
import com.nextdaymobilestudio.lebenindeutschland.core.database.entity.QuestionEntity
import com.nextdaymobilestudio.lebenindeutschland.core.database.entity.StudyAnswerEntity
import com.nextdaymobilestudio.lebenindeutschland.core.database.entity.StudyContentEntity
import com.nextdaymobilestudio.lebenindeutschland.core.database.entity.TranslationAnswerAuditEntity
import com.nextdaymobilestudio.lebenindeutschland.core.database.entity.TranslationAuditEntity

data class LocalQuestionBundle(
    val question: QuestionEntity,
    val answers: List<AnswerEntity>,
    val studyContent: StudyContentEntity,
    val studyAnswers: List<StudyAnswerEntity>,
    val translationAudit: TranslationAuditEntity?,
    val translationAnswerAudits: List<TranslationAnswerAuditEntity>,
)

data class ContentImage(
    val sourcePath: String,
    val sourceUrl: String,
    val storagePath: String,
    val sha256: String,
    val width: Int,
    val height: Int,
    val mimeType: String,
)

data class StudyAnswer(
    val id: String,
    val position: Int,
    val germanText: String,
    val localizedText: String,
    val image: ContentImage?,
)

data class StudyMemoryKey(
    val questionCue: String,
    val answerCue: String,
    val status: String,
)

data class StudyQuestion(
    val releaseId: String,
    val id: String,
    val officialNumber: Int,
    val scope: String,
    val stateCode: String?,
    val category: String?,
    val correctAnswerId: String,
    val germanQuestionText: String,
    val localizedQuestionText: String,
    val explanation: String,
    val languageCode: String,
    val questionImage: ContentImage?,
    val answers: List<StudyAnswer>,
    val explanationStatus: String,
    val reviewed: Boolean,
    val memoryKey: StudyMemoryKey? = null,
)

fun LocalQuestionBundle.toStudyQuestion(): StudyQuestion {
    val localizedAnswers = studyAnswers.associateBy(StudyAnswerEntity::answerId)
    val mappedAnswers = answers.sortedBy(AnswerEntity::displayOrder).map { germanAnswer ->
        val localizedAnswer = checkNotNull(localizedAnswers[germanAnswer.answerId]) {
            "Missing ${studyContent.languageCode} answer ${germanAnswer.answerId} for ${question.questionId}"
        }
        StudyAnswer(
            id = germanAnswer.answerId,
            position = germanAnswer.displayOrder,
            germanText = germanAnswer.germanText,
            localizedText = localizedAnswer.text,
            image = germanAnswer.toContentImage(),
        )
    }

    return StudyQuestion(
        releaseId = question.releaseId,
        id = question.questionId,
        officialNumber = question.officialNumber,
        scope = question.scope,
        stateCode = question.stateCode,
        category = question.category,
        correctAnswerId = question.correctAnswerId,
        germanQuestionText = question.germanQuestionText,
        localizedQuestionText = studyContent.questionText,
        explanation = studyContent.explanation,
        languageCode = studyContent.languageCode,
        questionImage = question.toContentImage(),
        answers = mappedAnswers,
        explanationStatus = studyContent.explanationStatus,
        reviewed = studyContent.reviewed,
        memoryKey = question.toStudyMemoryKey(),
    )
}

/** Every question and answer image this package references. */
fun ContentPackageSnapshot.contentImages(): List<ContentImage> =
    questions.mapNotNull { question -> question.toContentImage() } +
        answers.mapNotNull { answer -> answer.toContentImage() }

private fun QuestionEntity.toStudyMemoryKey(): StudyMemoryKey? {
    val values = listOf(memoryQuestionCue, memoryAnswerCue, memoryKeyStatus)
    if (values.all { it == null }) return null
    return StudyMemoryKey(
        questionCue = requireNotNull(memoryQuestionCue),
        answerCue = requireNotNull(memoryAnswerCue),
        status = requireNotNull(memoryKeyStatus),
    )
}

private fun QuestionEntity.toContentImage(): ContentImage? =
    contentImageOrNull(
        sourcePath = questionImageSourcePath,
        sourceUrl = questionImageSourceUrl,
        storagePath = questionImageStoragePath,
        sha256 = questionImageSha256,
        width = questionImageWidth,
        height = questionImageHeight,
        mimeType = questionImageMimeType,
    )

private fun AnswerEntity.toContentImage(): ContentImage? =
    contentImageOrNull(
        sourcePath = imageSourcePath,
        sourceUrl = imageSourceUrl,
        storagePath = imageStoragePath,
        sha256 = imageSha256,
        width = imageWidth,
        height = imageHeight,
        mimeType = imageMimeType,
    )

private fun contentImageOrNull(
    sourcePath: String?,
    sourceUrl: String?,
    storagePath: String?,
    sha256: String?,
    width: Int?,
    height: Int?,
    mimeType: String?,
): ContentImage? {
    if (
        sourcePath == null && sourceUrl == null && storagePath == null && sha256 == null &&
        width == null && height == null && mimeType == null
    ) {
        return null
    }

    return ContentImage(
        sourcePath = requireNotNull(sourcePath),
        sourceUrl = requireNotNull(sourceUrl),
        storagePath = requireNotNull(storagePath),
        sha256 = requireNotNull(sha256),
        width = requireNotNull(width),
        height = requireNotNull(height),
        mimeType = requireNotNull(mimeType),
    )
}
