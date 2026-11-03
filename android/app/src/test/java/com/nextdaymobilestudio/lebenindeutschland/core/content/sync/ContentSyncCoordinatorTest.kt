package com.nextdaymobilestudio.lebenindeutschland.core.content.sync

import com.nextdaymobilestudio.lebenindeutschland.core.content.remote.ContentAuthenticator
import com.nextdaymobilestudio.lebenindeutschland.core.content.remote.RemoteContentDataSource
import com.nextdaymobilestudio.lebenindeutschland.core.content.remote.RemoteContentDocument
import com.nextdaymobilestudio.lebenindeutschland.core.database.entity.ContentPackageEntity
import com.nextdaymobilestudio.lebenindeutschland.core.database.entity.ContentReleaseEntity
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.ContentPackageSnapshot
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyQuestion
import com.nextdaymobilestudio.lebenindeutschland.core.database.repository.LocalContentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSyncCoordinatorTest {
    @Test
    fun callerIsAuthenticatedBeforeTheFirstRemoteRead() = runBlocking {
        val fixture = remoteContentFixture()
        val remote = FakeRemoteContentDataSource(fixture)
        val local = RecordingLocalContentRepository()
        var authenticatedAfterCalls: Int? = null
        val authenticator = ContentAuthenticator { authenticatedAfterCalls = remote.calls.size }

        ContentSyncCoordinator(remote, local, authenticator) { 123L }.synchronize("en")

        assertEquals(0, authenticatedAfterCalls)
        assertTrue(remote.calls.isNotEmpty())
    }

    @Test
    fun failedAuthenticationOnFirstRunStopsTheSync() {
        val fixture = remoteContentFixture()
        val remote = FakeRemoteContentDataSource(fixture)
        val local = RecordingLocalContentRepository()
        val authenticator = ContentAuthenticator { throw ContentSyncException("no session") }

        assertThrows(ContentSyncException::class.java) {
            runBlocking {
                ContentSyncCoordinator(remote, local, authenticator) { 123L }.synchronize("en")
            }
        }

        assertEquals(emptyList<String>(), remote.calls)
        assertEquals(emptyList<ContentPackageSnapshot>(), local.snapshots)
    }

    @Test
    fun failedAuthenticationFallsBackToInstalledContent() = runBlocking {
        val fixture = remoteContentFixture()
        val local = RecordingLocalContentRepository()
        ContentSyncCoordinator(FakeRemoteContentDataSource(fixture), local) { 123L }
            .synchronize("en")

        val remote = FakeRemoteContentDataSource(fixture)
        val authenticator = ContentAuthenticator { throw ContentSyncException("no session") }
        val result = ContentSyncCoordinator(remote, local, authenticator) { 123L }
            .synchronize("en")

        assertTrue(result.usedLocalContent)
        assertFalse(result.activated)
        assertEquals(RELEASE_ID, result.releaseId)
        assertEquals(listOf("de", "en"), result.installedLanguageCodes)
        assertEquals(1, result.questionCount)
        assertEquals(emptyList<String>(), remote.calls)
    }

    @Test
    fun germanAndSelectedPackagesAreInstalledBeforeActivation() = runBlocking {
        val fixture = remoteContentFixture()
        val remote = FakeRemoteContentDataSource(fixture)
        val local = RecordingLocalContentRepository()
        val coordinator = ContentSyncCoordinator(remote, local) { 123L }

        val result = coordinator.synchronize("en")

        assertEquals(RELEASE_ID, result.releaseId)
        assertEquals("en", result.selectedLanguageCode)
        assertEquals(listOf("de", "en"), result.installedLanguageCodes)
        assertEquals(1, result.questionCount)
        assertTrue(result.activated)
        assertEquals(listOf("de", "en"), local.snapshots.map {
            it.packageMetadata.languageCode
        })
        assertEquals(RELEASE_ID to "en", local.activation)
        assertEquals(
            listOf(
                "manifest",
                "release:$RELEASE_ID",
                "package:$RELEASE_ID/de",
                "chunks:$RELEASE_ID/de",
                "package:$RELEASE_ID/en",
                "chunks:$RELEASE_ID/en",
            ),
            remote.calls,
        )
    }

    @Test
    fun germanSelectionDownloadsPackageOnlyOnce() = runBlocking {
        val fixture = remoteContentFixture()
        val remote = FakeRemoteContentDataSource(fixture)
        val local = RecordingLocalContentRepository()

        ContentSyncCoordinator(remote, local) { 123L }.synchronize("de")

        assertEquals(listOf("de"), local.snapshots.map { it.packageMetadata.languageCode })
        assertEquals(RELEASE_ID to "de", local.activation)
        assertEquals(1, remote.calls.count { it == "package:$RELEASE_ID/de" })
        assertEquals(1, remote.calls.count { it == "chunks:$RELEASE_ID/de" })
    }

    @Test
    fun activeImmutableReleaseSkipsPackageDownloadsAndActivation() = runBlocking {
        val fixture = remoteContentFixture()
        val remote = FakeRemoteContentDataSource(fixture)
        val local = RecordingLocalContentRepository().apply {
            seedActiveRelease(fixture, listOf("de", "en"))
        }

        val result = ContentSyncCoordinator(remote, local) { 123L }.synchronize("en")

        assertEquals(RELEASE_ID, result.releaseId)
        assertEquals(1, result.questionCount)
        assertEquals(false, result.activated)
        assertEquals(listOf("manifest"), remote.calls)
        assertTrue(local.snapshots.isEmpty())
        assertEquals(null, local.activation)
    }

    @Test
    fun invalidSelectedPackageCausesNoLocalWritesOrActivation() {
        val original = remoteContentFixture()
        val selectedChunk = original.chunks.getValue("en").single()
        val invalidFixture = original.copy(
            chunks = original.chunks + (
                "en" to listOf(
                    selectedChunk.copy(
                        fields = selectedChunk.fields + ("firstQuestionId" to "tampered"),
                    ),
                )
            ),
        )
        val local = RecordingLocalContentRepository()
        val coordinator = ContentSyncCoordinator(
            remoteContentDataSource = FakeRemoteContentDataSource(invalidFixture),
            localContentRepository = local,
            clock = { 123L },
        )

        assertThrows(ContentSyncException::class.java) {
            runBlocking { coordinator.synchronize("en") }
        }

        assertTrue(local.snapshots.isEmpty())
        assertEquals(null, local.activation)
    }

    @Test
    fun unavailableOrMalformedLanguageIsRejectedBeforePackageInstallation() {
        val fixture = remoteContentFixture()
        val local = RecordingLocalContentRepository()
        val coordinator = ContentSyncCoordinator(
            remoteContentDataSource = FakeRemoteContentDataSource(fixture),
            localContentRepository = local,
        )

        assertThrows(ContentSyncException::class.java) {
            runBlocking { coordinator.synchronize("tr") }
        }
        assertThrows(ContentSyncException::class.java) {
            runBlocking { coordinator.synchronize("../en") }
        }

        assertTrue(local.snapshots.isEmpty())
        assertEquals(null, local.activation)
    }
}

private class FakeRemoteContentDataSource(
    private val fixture: RemoteContentFixture,
) : RemoteContentDataSource {
    val calls = mutableListOf<String>()

    override suspend fun getActiveManifest(): RemoteContentDocument {
        calls += "manifest"
        return fixture.manifest
    }

    override suspend fun getRelease(releaseId: String): RemoteContentDocument {
        calls += "release:$releaseId"
        return fixture.release
    }

    override suspend fun getPackage(
        releaseId: String,
        languageCode: String,
    ): RemoteContentDocument {
        calls += "package:$releaseId/$languageCode"
        return fixture.packages.getValue(languageCode)
    }

    override suspend fun getChunks(
        releaseId: String,
        languageCode: String,
    ): List<RemoteContentDocument> {
        calls += "chunks:$releaseId/$languageCode"
        return fixture.chunks.getValue(languageCode)
    }
}

private class RecordingLocalContentRepository : LocalContentRepository {
    val snapshots = mutableListOf<ContentPackageSnapshot>()
    var activation: Pair<String, String>? = null

    private val activeRelease = MutableStateFlow<ContentReleaseEntity?>(null)
    private val installedPackages = MutableStateFlow<List<ContentPackageEntity>>(emptyList())

    override fun observeActiveRelease(): Flow<ContentReleaseEntity?> = activeRelease

    override fun observeInstalledPackages(releaseId: String): Flow<List<ContentPackageEntity>> =
        installedPackages

    override suspend fun getActiveRelease(): ContentReleaseEntity? = activeRelease.value

    override suspend fun getPackage(
        releaseId: String,
        languageCode: String,
    ): ContentPackageEntity? = installedPackages.value.firstOrNull {
        it.releaseId == releaseId && it.languageCode == languageCode
    }

    override suspend fun replacePackage(snapshot: ContentPackageSnapshot) {
        snapshots += snapshot
        installedPackages.value = snapshots.map(ContentPackageSnapshot::packageMetadata)
    }

    override suspend fun activateRelease(releaseId: String, requiredLanguageCode: String) {
        activation = releaseId to requiredLanguageCode
        activeRelease.value = snapshots.first().release.copy(isActive = true)
    }

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

    fun seedActiveRelease(
        fixture: RemoteContentFixture,
        languageCodes: List<String>,
    ) {
        activeRelease.value = ContentReleaseEntity(
            releaseId = RELEASE_ID,
            schemaVersion = 3,
            datasetVersion = "test-dataset",
            packageStrategy = "SELF_CONTAINED_LANGUAGE_PACK",
            status = "READY",
            createdAt = "2026-10-12T00:00:00Z",
            sourceCatalogSha256 = "a".repeat(64),
            catalogSha256 = "b".repeat(64),
            questionCount = 1,
            chunkSize = 50,
            languageCount = 2,
            qualityIssueCount = 0,
            installedAtEpochMillis = 123L,
            isActive = true,
        )
        installedPackages.value = languageCodes.map { languageCode ->
            val fields = fixture.packages.getValue(languageCode).fields
            ContentPackageEntity(
                releaseId = RELEASE_ID,
                languageCode = languageCode,
                schemaVersion = 3,
                packageStrategy = "SELF_CONTAINED_LANGUAGE_PACK",
                status = "READY",
                createdAt = "2026-10-12T00:00:00Z",
                packageSha256 = fields.getValue("packageSha256") as String,
                questionCount = 1,
                chunkCount = 1,
                chunkSize = 50,
                jsonBytes = fields.getValue("jsonBytes") as Long,
                qualityIssueCount = 0,
                explanationReady = true,
                installedAtEpochMillis = 123L,
            )
        }
    }
}
