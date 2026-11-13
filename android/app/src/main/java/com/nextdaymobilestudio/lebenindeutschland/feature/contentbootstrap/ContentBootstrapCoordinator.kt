package com.nextdaymobilestudio.lebenindeutschland.feature.contentbootstrap

import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.ContentRefresher
import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.ContentSynchronizer
import com.nextdaymobilestudio.lebenindeutschland.core.database.entity.ContentReleaseEntity
import com.nextdaymobilestudio.lebenindeutschland.core.database.repository.LocalContentRepository
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ContentBootstrapCoordinator(
    private val localContentRepository: LocalContentRepository,
    private val contentSynchronizerProvider: () -> ContentSynchronizer,
    private val contentRefresher: ContentRefresher = ContentRefresher.None,
    private val reportError: (String, Throwable) -> Unit = { _, _ -> },
) {
    fun bootstrap(selectedLanguageCode: String): Flow<ContentBootstrapUiState> = flow {
        // A refresh started for an earlier language could still activate a release
        // without the language checked below. Let it stop before checking.
        contentRefresher.cancelAndJoin()

        val localRelease = findUsableLocalRelease(selectedLanguageCode)
        if (localRelease != null) {
            // Installed content is usable now (ADR 0006). The server check continues in
            // the background, so a slow or unreachable server cannot hold up the learner.
            contentRefresher.start(selectedLanguageCode)
            emit(localRelease.toReadyState())
            return@flow
        }

        emit(ContentBootstrapUiState.Loading)
        try {
            val result = contentSynchronizerProvider().synchronize(selectedLanguageCode)
            emit(
                ContentBootstrapUiState.Ready(
                    releaseId = result.releaseId,
                    questionCount = result.questionCount,
                    isRefreshing = false,
                ),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            reportError(
                "Content bootstrap failed for language $selectedLanguageCode",
                exception,
            )
            emit(ContentBootstrapUiState.Error)
        }
    }

    private suspend fun findUsableLocalRelease(
        selectedLanguageCode: String,
    ): ContentReleaseEntity? {
        val release = localContentRepository.getActiveRelease() ?: return null
        val requiredLanguages = setOf(GERMAN_LANGUAGE_CODE, selectedLanguageCode)
        val hasEveryRequiredPackage = requiredLanguages.all { languageCode ->
            localContentRepository.getPackage(release.releaseId, languageCode) != null
        }
        return release.takeIf { hasEveryRequiredPackage }
    }

    private fun ContentReleaseEntity.toReadyState(): ContentBootstrapUiState.Ready =
        ContentBootstrapUiState.Ready(
            releaseId = releaseId,
            questionCount = questionCount,
            isRefreshing = false,
        )

    private companion object {
        const val GERMAN_LANGUAGE_CODE = "de"
    }
}
