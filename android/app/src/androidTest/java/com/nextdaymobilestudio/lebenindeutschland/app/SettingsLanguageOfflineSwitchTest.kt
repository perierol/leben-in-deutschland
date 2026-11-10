package com.nextdaymobilestudio.lebenindeutschland.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.ContentSyncException
import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.ContentSyncResult
import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.ContentSynchronizer
import com.nextdaymobilestudio.lebenindeutschland.core.database.entity.ContentPackageEntity
import com.nextdaymobilestudio.lebenindeutschland.core.database.entity.ContentReleaseEntity
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.ContentPackageSnapshot
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyAnswer
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyQuestion
import com.nextdaymobilestudio.lebenindeutschland.core.database.repository.LocalContentRepository
import com.nextdaymobilestudio.lebenindeutschland.core.preferences.OnboardingPreferences
import com.nextdaymobilestudio.lebenindeutschland.core.preferences.OnboardingPreferencesRepository
import com.nextdaymobilestudio.lebenindeutschland.feature.contentbootstrap.ui.ContentBootstrapTestTags
import com.nextdaymobilestudio.lebenindeutschland.feature.home.ui.HomeTestTags
import com.nextdaymobilestudio.lebenindeutschland.feature.languageselection.model.SupportedLanguage
import com.nextdaymobilestudio.lebenindeutschland.feature.languageselection.ui.LanguageSelectionTestTags
import com.nextdaymobilestudio.lebenindeutschland.feature.mockexam.MockExamSnapshot
import com.nextdaymobilestudio.lebenindeutschland.feature.mockexam.data.MockExamPreferencesRepository
import com.nextdaymobilestudio.lebenindeutschland.feature.settings.ui.SettingsTestTags
import com.nextdaymobilestudio.lebenindeutschland.feature.studysession.ui.StudySessionTestTags
import com.nextdaymobilestudio.lebenindeutschland.ui.theme.LebenInDeutschlandTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for audit finding A1: choosing an uninstalled language in Settings
 * while offline must not lock the learner out of their installed content.
 *
 * Runs the real app navigation with in-memory repositories and a synchronizer that
 * fails for Arabic the way an offline device does, so the result is deterministic.
 */
@RunWith(AndroidJUnit4::class)
class SettingsLanguageOfflineSwitchTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val preferences = InMemoryOnboardingPreferences(languageCode = "en", stateCode = "be")
    private val appliedLanguages = mutableListOf<SupportedLanguage>()
    private val synchronizer = ContentSynchronizer { languageCode ->
        if (languageCode != "en") {
            throw ContentSyncException("Simulated offline download of $languageCode")
        }
        // Offline with English installed: the sync falls back to local content.
        ContentSyncResult(
            releaseId = RELEASE_ID,
            selectedLanguageCode = "en",
            installedLanguageCodes = listOf("de", "en"),
            questionCount = 1,
            activated = false,
            usedLocalContent = true,
        )
    }

    @Test
    fun offlineSwitchToUninstalledLanguage_backReturnsToInstalledEnglishContent() {
        var launch by mutableIntStateOf(0)
        composeRule.setContent {
            key(launch) {
                LebenInDeutschlandTheme {
                    LebenInDeutschlandApp(
                        onboardingPreferencesRepository = preferences,
                        localContentRepository = EnglishOnlyContentRepository,
                        mockExamPreferencesRepository = NoMockExamPreferences,
                        contentSynchronizerProvider = { synchronizer },
                        onApplicationLanguageSelected = { language -> appliedLanguages += language },
                    )
                }
            }
        }

        // 1. English content is installed.
        waitForTag(HomeTestTags.SCREEN)

        // 2. In Settings, choose Arabic, which is not installed.
        composeRule.onNodeWithTag(HomeTestTags.OPEN_SETTINGS).performClick()
        waitForTag(SettingsTestTags.SCREEN)
        composeRule.onNodeWithTag(SettingsTestTags.LANGUAGE).performClick()
        waitForTag(LanguageSelectionTestTags.SCREEN)
        composeRule.onNodeWithTag(LanguageSelectionTestTags.SEARCH).performTextReplacement("Arabisch")
        composeRule.onNodeWithTag(LanguageSelectionTestTags.option("ar")).performClick()
        composeRule.onNodeWithTag(LanguageSelectionTestTags.CONTINUE).performClick()

        // 3. The offline download fails.
        waitForTag(ContentBootstrapTestTags.ERROR)
        assertEquals("en", preferences.current.value.languageCode)

        // 4. Press Back.
        Espresso.pressBack()

        // 5. A usable screen, English still saved, installed content reachable.
        waitForTag(LanguageSelectionTestTags.SCREEN)
        composeRule.onNodeWithTag(ContentBootstrapTestTags.ERROR).assertDoesNotExist()
        composeRule.onNodeWithTag(LanguageSelectionTestTags.BACK).performClick()
        waitForTag(SettingsTestTags.SCREEN)
        composeRule.onNodeWithTag(SettingsTestTags.BACK).performClick()
        waitForTag(HomeTestTags.SCREEN)
        composeRule.runOnIdle {
            assertEquals("en", preferences.current.value.languageCode)
            assertEquals(emptyList<SupportedLanguage>(), appliedLanguages)
        }
        assertEnglishQuestionOpensFromHome()

        // 6. Relaunch: a fresh composition reads only the persisted preferences.
        Espresso.pressBack()
        waitForTag(HomeTestTags.SCREEN)
        composeRule.runOnIdle { launch += 1 }
        waitForTag(HomeTestTags.SCREEN)
        assertEquals("en", preferences.current.value.languageCode)
        assertEnglishQuestionOpensFromHome()
    }

    private fun assertEnglishQuestionOpensFromHome() {
        composeRule.onNodeWithTag(HomeTestTags.START_STUDY).performClick()
        waitForTag(StudySessionTestTags.QUESTION)
        composeRule.onNodeWithText(ENGLISH_QUESTION_TEXT).assertIsDisplayed()
    }

    private fun waitForTag(tag: String) {
        try {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            composeRule.onRoot().printToLog("A1-$tag")
            throw timeout
        }
    }

    private class InMemoryOnboardingPreferences(
        languageCode: String,
        stateCode: String,
    ) : OnboardingPreferencesRepository {
        val current = MutableStateFlow(OnboardingPreferences(languageCode, stateCode))
        override val preferences: Flow<OnboardingPreferences> = current

        override suspend fun setLanguageCode(languageCode: String) {
            current.value = current.value.copy(languageCode = languageCode)
        }

        override suspend fun setStateCode(stateCode: String) {
            current.value = current.value.copy(stateCode = stateCode)
        }
    }

    private object NoMockExamPreferences : MockExamPreferencesRepository {
        override val exam: Flow<MockExamSnapshot?> = flowOf(null)

        override suspend fun getExam(): MockExamSnapshot? = null

        override suspend fun saveExam(exam: MockExamSnapshot) = Unit

        override suspend fun clearExam() = Unit
    }

    /** One active release with the German and English packages installed, and no Arabic. */
    private object EnglishOnlyContentRepository : LocalContentRepository {
        private val installedLanguages = setOf("de", "en")

        private val release = ContentReleaseEntity(
            releaseId = RELEASE_ID,
            schemaVersion = 3,
            datasetVersion = "test",
            packageStrategy = "language-packs",
            status = "published",
            createdAt = "2026-11-10T00:00:00Z",
            sourceCatalogSha256 = "source",
            catalogSha256 = "catalog",
            questionCount = 1,
            chunkSize = 50,
            languageCount = 2,
            qualityIssueCount = 0,
            installedAtEpochMillis = 0L,
            isActive = true,
        )

        override fun observeActiveRelease(): Flow<ContentReleaseEntity?> = flowOf(release)

        override fun observeInstalledPackages(releaseId: String): Flow<List<ContentPackageEntity>> =
            flowOf(installedLanguages.map(::packageFor))

        override suspend fun getActiveRelease(): ContentReleaseEntity = release

        override suspend fun getPackage(
            releaseId: String,
            languageCode: String,
        ): ContentPackageEntity? = packageFor(languageCode)
            .takeIf { releaseId == RELEASE_ID && languageCode in installedLanguages }

        override suspend fun replacePackage(snapshot: ContentPackageSnapshot) =
            error("Offline: nothing can be installed")

        override suspend fun activateRelease(releaseId: String, requiredLanguageCode: String) =
            error("Offline: nothing can be activated")

        override suspend fun deleteRelease(releaseId: String) = Unit

        override suspend fun getEligibleQuestionIds(
            releaseId: String,
            languageCode: String,
            stateCode: String?,
        ): List<String> = if (languageCode in installedLanguages) listOf(QUESTION_ID) else emptyList()

        override suspend fun getStudyQuestion(
            releaseId: String,
            questionId: String,
            languageCode: String,
        ): StudyQuestion? = if (languageCode == "en" && questionId == QUESTION_ID) {
            englishQuestion
        } else {
            null
        }

        private fun packageFor(languageCode: String) = ContentPackageEntity(
            releaseId = RELEASE_ID,
            languageCode = languageCode,
            schemaVersion = 3,
            packageStrategy = "language-packs",
            status = "published",
            createdAt = "2026-11-10T00:00:00Z",
            packageSha256 = "package-$languageCode",
            questionCount = 1,
            chunkCount = 1,
            chunkSize = 50,
            jsonBytes = 1L,
            qualityIssueCount = 0,
            explanationReady = true,
            installedAtEpochMillis = 0L,
        )

        private val englishQuestion = StudyQuestion(
            releaseId = RELEASE_ID,
            id = QUESTION_ID,
            officialNumber = 1,
            scope = "GENERAL",
            stateCode = null,
            category = null,
            correctAnswerId = "A",
            germanQuestionText = "Was ist richtig?",
            localizedQuestionText = ENGLISH_QUESTION_TEXT,
            explanation = "Explanation.",
            languageCode = "en",
            questionImage = null,
            answers = listOf(
                StudyAnswer("A", 0, "Antwort A", "Answer A", null),
                StudyAnswer("B", 1, "Antwort B", "Answer B", null),
            ),
            explanationStatus = "AI_GENERATED_NEEDS_REVIEW",
            reviewed = false,
            memoryKey = null,
        )
    }

    private companion object {
        const val RELEASE_ID = "release-test"
        const val QUESTION_ID = "general_001"
        const val ENGLISH_QUESTION_TEXT = "Which one is correct?"
    }
}
