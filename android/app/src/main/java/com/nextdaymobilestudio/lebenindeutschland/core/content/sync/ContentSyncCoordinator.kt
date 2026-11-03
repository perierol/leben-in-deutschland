package com.nextdaymobilestudio.lebenindeutschland.core.content.sync

import com.nextdaymobilestudio.lebenindeutschland.core.content.remote.ContentAuthenticator
import com.nextdaymobilestudio.lebenindeutschland.core.content.remote.RemoteContentDataSource
import com.nextdaymobilestudio.lebenindeutschland.core.database.repository.LocalContentRepository
import java.util.concurrent.CancellationException

data class ContentSyncResult(
    val releaseId: String,
    val selectedLanguageCode: String,
    val installedLanguageCodes: List<String>,
    val questionCount: Int,
    val activated: Boolean,
    /**
     * True when the sync could not reach the catalogue and the already installed
     * content was used instead. The app is fully usable; it may simply be a release
     * behind.
     */
    val usedLocalContent: Boolean = false,
)

fun interface ContentSynchronizer {
    suspend fun synchronize(selectedLanguageCode: String): ContentSyncResult
}

class ContentSyncCoordinator(
    private val remoteContentDataSource: RemoteContentDataSource,
    private val localContentRepository: LocalContentRepository,
    private val contentAuthenticator: ContentAuthenticator = ContentAuthenticator.None,
    private val clock: () -> Long = System::currentTimeMillis,
) : ContentSynchronizer {
    private val mapper = ContentPackageMapper()

    override suspend fun synchronize(selectedLanguageCode: String): ContentSyncResult {
        validateLanguageCode(selectedLanguageCode)

        val requiredLanguages = listOf(GERMAN_LANGUAGE_CODE, selectedLanguageCode).distinct()

        // Firestore serves the catalogue only to an authenticated caller, so a session
        // has to exist before the first read. Anonymous sign-in is invisible to the
        // user but can still fail — offline, without Play Services, or during a
        // Firebase outage. When it does and the device already holds the content it
        // needs, studying continues on what is installed rather than the app refusing
        // to start. Only a first run, which has nothing to fall back to, reports the
        // failure.
        try {
            contentAuthenticator.ensureAuthenticated()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            return installedContentResult(selectedLanguageCode, requiredLanguages)
                ?: throw cause
        }

        val manifest = remoteContentDataSource.getActiveManifest()
        val releaseId = manifest.fields["activeReleaseId"] as? String
            ?: throw ContentSyncException("Active manifest has no activeReleaseId")
        if (!RELEASE_ID.matches(releaseId)) {
            throw ContentSyncException("Invalid active release ID: $releaseId")
        }

        val activeLocalRelease = localContentRepository.getActiveRelease()
        val hasEveryRequiredPackage = activeLocalRelease?.releaseId == releaseId &&
            requiredLanguages.all { languageCode ->
                localContentRepository.getPackage(releaseId, languageCode) != null
            }
        if (hasEveryRequiredPackage) {
            return ContentSyncResult(
                releaseId = releaseId,
                selectedLanguageCode = selectedLanguageCode,
                installedLanguageCodes = requiredLanguages,
                questionCount = requireNotNull(activeLocalRelease).questionCount,
                activated = false,
            )
        }

        val releaseDocument = remoteContentDataSource.getRelease(releaseId)
        val installedAtEpochMillis = clock()
        val release = mapper.validateRelease(
            manifestDocument = manifest,
            releaseDocument = releaseDocument,
            installedAtEpochMillis = installedAtEpochMillis,
        )

        if (selectedLanguageCode !in release.packageHashes) {
            throw ContentSyncException(
                "Selected language is not available in release: $selectedLanguageCode",
            )
        }

        // Validate every required package before the first local write. A failed or
        // incomplete download can therefore never replace currently usable content.
        val snapshots = requiredLanguages.map { languageCode ->
            mapper.mapPackage(
                release = release,
                packageDocument = remoteContentDataSource.getPackage(
                    releaseId = releaseId,
                    languageCode = languageCode,
                ),
                chunkDocuments = remoteContentDataSource.getChunks(
                    releaseId = releaseId,
                    languageCode = languageCode,
                ),
                installedAtEpochMillis = installedAtEpochMillis,
            )
        }

        snapshots.forEach { snapshot ->
            localContentRepository.replacePackage(snapshot)
        }
        localContentRepository.activateRelease(releaseId, selectedLanguageCode)

        return ContentSyncResult(
            releaseId = releaseId,
            selectedLanguageCode = selectedLanguageCode,
            installedLanguageCodes = requiredLanguages,
            questionCount = release.entity.questionCount,
            activated = true,
        )
    }

    /**
     * The installed content, when it already covers every required language.
     * Returns null when nothing usable is installed, which leaves the caller to
     * report the original failure.
     */
    private suspend fun installedContentResult(
        selectedLanguageCode: String,
        requiredLanguages: List<String>,
    ): ContentSyncResult? {
        val activeLocalRelease = localContentRepository.getActiveRelease() ?: return null
        val hasEveryRequiredPackage = requiredLanguages.all { languageCode ->
            localContentRepository.getPackage(
                releaseId = activeLocalRelease.releaseId,
                languageCode = languageCode,
            ) != null
        }
        if (!hasEveryRequiredPackage) return null

        return ContentSyncResult(
            releaseId = activeLocalRelease.releaseId,
            selectedLanguageCode = selectedLanguageCode,
            installedLanguageCodes = requiredLanguages,
            questionCount = activeLocalRelease.questionCount,
            activated = false,
            usedLocalContent = true,
        )
    }

    private fun validateLanguageCode(languageCode: String) {
        if (!LANGUAGE_CODE.matches(languageCode)) {
            throw ContentSyncException("Invalid language code: $languageCode")
        }
    }

    private companion object {
        val LANGUAGE_CODE = Regex("^[a-z]{2,3}(?:-[A-Za-z]{4})?$")
        val RELEASE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        const val GERMAN_LANGUAGE_CODE = "de"
    }
}
