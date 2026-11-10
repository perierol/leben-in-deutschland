package com.nextdaymobilestudio.lebenindeutschland.app

import androidx.navigation3.runtime.NavKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.HomeKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.SettingsKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.SettingsLanguageDownloadKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.SettingsLanguageKey
import com.nextdaymobilestudio.lebenindeutschland.core.preferences.OnboardingPreferences
import com.nextdaymobilestudio.lebenindeutschland.core.preferences.OnboardingPreferencesRepository
import com.nextdaymobilestudio.lebenindeutschland.feature.languageselection.model.SupportedLanguage
import com.nextdaymobilestudio.lebenindeutschland.feature.mockexam.FakeMockExamPreferencesRepository
import com.nextdaymobilestudio.lebenindeutschland.feature.mockexam.MockExamSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsLanguageChangeTest {
    private val settingsStack = listOf(HomeKey(questionCount = 310), SettingsKey, SettingsLanguageKey)
    private val backStack = settingsStack.toMutableList<NavKey>()
    private val preferences = FakeOnboardingPreferencesRepository(languageCode = "en")
    private val mockExamPreferences = FakeMockExamPreferencesRepository()
    private val appliedLanguages = mutableListOf<SupportedLanguage>()
    private val change = SettingsLanguageChange(
        backStack = backStack,
        onboardingPreferencesRepository = preferences,
        mockExamPreferencesRepository = mockExamPreferences,
        onApplicationLanguageSelected = appliedLanguages::add,
        navigateBack = { backStack.removeAt(backStack.lastIndex) },
    )

    @Test
    fun `offline switch to an uninstalled language keeps installed content reachable`() = runTest {
        mockExamPreferences.saveExam(activeExam)

        // Arabic is not installed and the device is offline, so its download never
        // completes. Nothing may be committed before that download succeeds.
        change.request(SupportedLanguage.ARABIC, persistedLanguage = SupportedLanguage.ENGLISH)

        assertEquals("en", preferences.current.value.languageCode)
        assertEquals(emptyList<SupportedLanguage>(), appliedLanguages)
        assertNotNull(mockExamPreferences.getExam())
        assertEquals(settingsStack, backStack.dropLast(1))
    }

    @Test
    fun `installed language is committed once its content is ready`() = runTest {
        mockExamPreferences.saveExam(activeExam)
        change.request(SupportedLanguage.ARABIC, persistedLanguage = SupportedLanguage.ENGLISH)

        change.complete(SettingsLanguageDownloadKey("ar"), questionCount = 310)

        assertEquals("ar", preferences.current.value.languageCode)
        assertEquals(listOf(SupportedLanguage.ARABIC), appliedLanguages)
        assertNull(mockExamPreferences.getExam())
        assertEquals(listOf<NavKey>(HomeKey(questionCount = 310)), backStack)
    }

    private class FakeOnboardingPreferencesRepository(
        languageCode: String,
    ) : OnboardingPreferencesRepository {
        val current = MutableStateFlow(OnboardingPreferences(languageCode, stateCode = "BE"))
        override val preferences = current

        override suspend fun setLanguageCode(languageCode: String) {
            current.value = current.value.copy(languageCode = languageCode)
        }

        override suspend fun setStateCode(stateCode: String) {
            current.value = current.value.copy(stateCode = stateCode)
        }
    }

    private companion object {
        val activeExam = MockExamSnapshot(
            releaseId = "release-1",
            languageCode = "en",
            stateCode = "BE",
            questionIds = List(33) { index -> "q$index" },
            startedAtEpochMillis = 0L,
            deadlineEpochMillis = 3_600_000L,
        )
    }
}
