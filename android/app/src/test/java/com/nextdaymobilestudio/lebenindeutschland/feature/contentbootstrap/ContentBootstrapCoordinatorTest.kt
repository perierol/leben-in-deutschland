package com.nextdaymobilestudio.lebenindeutschland.feature.contentbootstrap

import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.ContentRefresher
import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.ContentSyncResult
import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.ContentSynchronizer
import com.nextdaymobilestudio.lebenindeutschland.core.database.entity.ContentPackageEntity
import com.nextdaymobilestudio.lebenindeutschland.core.database.entity.ContentReleaseEntity
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.ContentPackageSnapshot
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyQuestion
import com.nextdaymobilestudio.lebenindeutschland.core.database.repository.LocalContentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ContentBootstrapCoordinatorTest {
    @Test
    fun firstInstallShowsLoadingThenReady() = runBlocking {
        val local = FakeLocalContentRepository()
        val coordinator = ContentBootstrapCoordinator(
            localContentRepository = local,
            contentSynchronizerProvider = {
                successfulSynchronizer(local)
            },
        )

        val states = coordinator.bootstrap("en").toList()

        assertEquals(
            listOf(
                ContentBootstrapUiState.Loading,
                ContentBootstrapUiState.Ready(
                    releaseId = RELEASE_ID,
                    questionCount = 460,
                    isRefreshing = false,
                ),
            ),
            states,
        )
    }

    @Test
    fun usableLocalContentIsReadyAtOnceAndRefreshesInBackground() = runBlocking {
        val local = FakeLocalContentRepository().apply {
            seedUsableContent("en")
        }
        val refresher = RecordingContentRefresher()
        val coordinator = ContentBootstrapCoordinator(
            localContentRepository = local,
            contentSynchronizerProvider = {
                error("Installed content must not wait for a foreground sync")
            },
            contentRefresher = refresher,
        )

        val states = coordinator.bootstrap("en").toList()

        assertEquals(
            listOf(
                ContentBootstrapUiState.Ready(
                    releaseId = RELEASE_ID,
                    questionCount = 460,
                    isRefreshing = false,
                ),
            ),
            states,
        )
        assertEquals(listOf("cancelAndJoin", "start:en"), refresher.calls)
    }

    @Test
    fun firstInstallStopsEarlierRefreshAndStartsNone() = runBlocking {
        val local = FakeLocalContentRepository()
        val refresher = RecordingContentRefresher()
        val coordinator = ContentBootstrapCoordinator(
            localContentRepository = local,
            contentSynchronizerProvider = { successfulSynchronizer(local) },
            contentRefresher = refresher,
        )

        coordinator.bootstrap("tr").toList()

        // A refresh for the previous language must not activate a release lacking "tr"
        // after the check, and the foreground download already contacted the server.
        assertEquals(listOf("cancelAndJoin"), refresher.calls)
    }

    @Test
    fun firstInstallFailureCanBeRetried() = runBlocking {
        val coordinator = ContentBootstrapCoordinator(
            localContentRepository = FakeLocalContentRepository(),
            contentSynchronizerProvider = {
                ContentSynchronizer { error("offline") }
            },
        )

        assertEquals(
            listOf(
                ContentBootstrapUiState.Loading,
                ContentBootstrapUiState.Error,
            ),
            coordinator.bootstrap("tr").toList(),
        )
    }

    @Test
    fun activeReleaseWithoutSelectedLanguageIsNotUsableOffline() = runBlocking {
        val local = FakeLocalContentRepository().apply {
            seedUsableContent("de")
        }
        val coordinator = ContentBootstrapCoordinator(
            localContentRepository = local,
            contentSynchronizerProvider = {
                ContentSynchronizer { error("offline") }
            },
        )

        assertEquals(
            listOf(
                ContentBootstrapUiState.Loading,
                ContentBootstrapUiState.Error,
            ),
            coordinator.bootstrap("en").toList(),
        )
    }

    @Test
    fun synchronizationFailureIsReportedWithoutChangingUiFallback() = runBlocking {
        val expectedException = IllegalStateException("offline")
        var reportedMessage: String? = null
        var reportedThrowable: Throwable? = null
        val coordinator = ContentBootstrapCoordinator(
            localContentRepository = FakeLocalContentRepository(),
            contentSynchronizerProvider = {
                ContentSynchronizer { throw expectedException }
            },
            reportError = { message, throwable ->
                reportedMessage = message
                reportedThrowable = throwable
            },
        )

        val states = coordinator.bootstrap("tr").toList()

        assertEquals(
            listOf(
                ContentBootstrapUiState.Loading,
                ContentBootstrapUiState.Error,
            ),
            states,
        )
        assertEquals(
            "Content bootstrap failed for language tr",
            reportedMessage,
        )
        assertSame(expectedException, reportedThrowable)
    }
}

private fun successfulSynchronizer(
    local: FakeLocalContentRepository,
): ContentSynchronizer = ContentSynchronizer { languageCode ->
    local.seedUsableContent(languageCode)
    ContentSyncResult(
        releaseId = RELEASE_ID,
        selectedLanguageCode = languageCode,
        installedLanguageCodes = listOf("de", languageCode).distinct(),
        questionCount = 460,
        activated = true,
    )
}

private class RecordingContentRefresher : ContentRefresher {
    val calls = mutableListOf<String>()

    override suspend fun start(languageCode: String) {
        calls += "start:$languageCode"
    }

    override suspend fun cancelAndJoin() {
        calls += "cancelAndJoin"
    }
}

internal class FakeLocalContentRepository : LocalContentRepository {
    private val activeRelease = MutableStateFlow<ContentReleaseEntity?>(null)
    private val packages = mutableMapOf<Pair<String, String>, ContentPackageEntity>()

    override fun observeActiveRelease(): Flow<ContentReleaseEntity?> = activeRelease

    override fun observeInstalledPackages(releaseId: String): Flow<List<ContentPackageEntity>> =
        MutableStateFlow(
            packages.values.filter { packageEntity ->
                packageEntity.releaseId == releaseId
            },
        )

    override suspend fun getActiveRelease(): ContentReleaseEntity? = activeRelease.value

    override suspend fun getPackage(
        releaseId: String,
        languageCode: String,
    ): ContentPackageEntity? = packages[releaseId to languageCode]

    override suspend fun replacePackage(snapshot: ContentPackageSnapshot) = Unit

    override suspend fun activateRelease(releaseId: String, requiredLanguageCode: String) = Unit

    override suspend fun deleteRelease(releaseId: String) = Unit

    override suspend fun getEligibleQuestionIds(
        releaseId: String,
        languageCode: String,
        stateCode: String?,
    ): List<String> = emptyList()

    override suspend fun getStudyQuestion(
        releaseId: String,
        questionId: String,
        languageCode: String,
    ): StudyQuestion? = null

    fun seedUsableContent(selectedLanguageCode: String) {
        activeRelease.value = releaseEntity()
        setOf("de", selectedLanguageCode).forEach { languageCode ->
            packages[RELEASE_ID to languageCode] = packageEntity(languageCode)
        }
    }
}

private fun releaseEntity(): ContentReleaseEntity = ContentReleaseEntity(
    releaseId = RELEASE_ID,
    schemaVersion = 3,
    datasetVersion = "2026-10",
    packageStrategy = "SELF_CONTAINED_LANGUAGE_PACK",
    status = "READY",
    createdAt = "2026-10-12T00:00:00Z",
    sourceCatalogSha256 = "a".repeat(64),
    catalogSha256 = "b".repeat(64),
    questionCount = 460,
    chunkSize = 10,
    languageCount = 21,
    qualityIssueCount = 0,
    installedAtEpochMillis = 123L,
    isActive = true,
)

private fun packageEntity(languageCode: String): ContentPackageEntity = ContentPackageEntity(
    releaseId = RELEASE_ID,
    languageCode = languageCode,
    schemaVersion = 3,
    packageStrategy = "SELF_CONTAINED_LANGUAGE_PACK",
    status = "READY",
    createdAt = "2026-10-12T00:00:00Z",
    packageSha256 = "c".repeat(64),
    questionCount = 460,
    chunkCount = 46,
    chunkSize = 10,
    jsonBytes = 1_000L,
    qualityIssueCount = 0,
    explanationReady = true,
    installedAtEpochMillis = 123L,
)

private const val RELEASE_ID = "lid-2026-10-05-103327-schema-3"
