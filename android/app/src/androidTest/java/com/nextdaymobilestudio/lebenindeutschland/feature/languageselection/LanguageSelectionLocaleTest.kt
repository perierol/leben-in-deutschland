package com.nextdaymobilestudio.lebenindeutschland.feature.languageselection

import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nextdaymobilestudio.lebenindeutschland.MainActivity
import com.nextdaymobilestudio.lebenindeutschland.core.preferences.DataStoreOnboardingPreferencesRepository
import com.nextdaymobilestudio.lebenindeutschland.core.preferences.onboardingPreferencesDataStore
import com.nextdaymobilestudio.lebenindeutschland.feature.home.ui.HomeTestTags
import com.nextdaymobilestudio.lebenindeutschland.feature.languageselection.ui.LanguageSelectionTestTags
import com.nextdaymobilestudio.lebenindeutschland.feature.stateselection.ui.StateSelectionTestTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageSelectionLocaleTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun startFromGerman() {
        clearOnboardingSelections()
        setApplicationLocale("de")
        composeRule.activityRule.scenario.recreate()
        waitForText("Sprache auswählen")
    }

    @After
    fun returnToSystemLocale() {
        clearOnboardingSelections()
        setApplicationLocale("")
    }

    @Test
    fun continueAppliesLocaleAndNavigationSurvivesActivityRecreation() {
        composeRule
            .onNodeWithTag(LanguageSelectionTestTags.option("en"))
            .performClick()
        composeRule
            .onNodeWithTag(LanguageSelectionTestTags.CONTINUE)
            .performClick()

        waitForApplicationLocale("en")
        waitForText("Select federal state")
        composeRule.onNodeWithText("Select federal state").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        waitForApplicationLocale("en")
        waitForText("Select federal state")
        composeRule.onNodeWithText("Select federal state").assertIsDisplayed()

        composeRule
            .onNodeWithTag(StateSelectionTestTags.BACK)
            .performClick()
        waitForText("Select language")
        composeRule
            .onNodeWithTag(LanguageSelectionTestTags.option("en"))
            .assertIsSelected()
    }

    @Test
    fun arabicLocaleUsesRightToLeftLayout() {
        composeRule
            .onNodeWithTag(LanguageSelectionTestTags.SEARCH)
            .performTextReplacement("ar")
        composeRule
            .onNodeWithTag(LanguageSelectionTestTags.option("ar"))
            .performClick()
        composeRule
            .onNodeWithTag(LanguageSelectionTestTags.CONTINUE)
            .performClick()

        waitForApplicationLocale("ar")
        waitForText("اختر الولاية الألمانية")
        composeRule.onNodeWithText("اختر الولاية الألمانية").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(
                View.LAYOUT_DIRECTION_RTL,
                composeRule.activity.resources.configuration.layoutDirection,
            )
        }
    }

    @Test
    fun persistedLanguageAndStateOpenHomeAfterActivityRecreation() {
        composeRule
            .onNodeWithTag(LanguageSelectionTestTags.CONTINUE)
            .performClick()
        waitForText("Bundesland auswählen")

        composeRule
            .onNodeWithTag(StateSelectionTestTags.option("bw"))
            .performClick()
        composeRule
            .onNodeWithTag(StateSelectionTestTags.CONTINUE)
            .performClick()

        // On a clean install Home waits for anonymous sign-in and the first content
        // download from Firebase, which can take longer than the shared 10-second wait.
        composeRule.waitUntil(timeoutMillis = FIRST_INSTALL_HOME_TIMEOUT_MILLIS) {
            composeRule
                .onAllNodesWithTag(HomeTestTags.SCREEN)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        waitForPersistedSelections(languageCode = "de", stateCode = "bw")
        composeRule.activityRule.scenario.recreate()

        waitForTag(HomeTestTags.SCREEN)
        composeRule
            .onNodeWithTag(HomeTestTags.SCREEN)
            .assertIsDisplayed()
    }

    private fun clearOnboardingSelections() {
        val repository = DataStoreOnboardingPreferencesRepository(
            composeRule.activity.applicationContext.onboardingPreferencesDataStore,
        )
        runBlocking { repository.clear() }
    }

    private fun waitForPersistedSelections(
        languageCode: String,
        stateCode: String,
    ) {
        val repository = DataStoreOnboardingPreferencesRepository(
            composeRule.activity.applicationContext.onboardingPreferencesDataStore,
        )
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking {
                repository.preferences.first().let { preferences ->
                    preferences.languageCode == languageCode &&
                        preferences.stateCode == stateCode
                }
            }
        }
    }

    private fun setApplicationLocale(languageTag: String) {
        composeRule.runOnUiThread {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(languageTag),
            )
        }
        waitForApplicationLocale(languageTag)
    }

    private fun waitForApplicationLocale(languageTag: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            AppCompatDelegate.getApplicationLocales().toLanguageTags() == languageTag
        }
        composeRule.waitForIdle()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule
                .onAllNodesWithText(text)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule
                .onAllNodesWithTag(tag)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private companion object {
        const val FIRST_INSTALL_HOME_TIMEOUT_MILLIS = 30_000L
    }
}
