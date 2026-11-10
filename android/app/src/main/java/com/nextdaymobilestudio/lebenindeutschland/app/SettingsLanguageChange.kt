package com.nextdaymobilestudio.lebenindeutschland.app

import androidx.navigation3.runtime.NavKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.HomeKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.SettingsLanguageDownloadKey
import com.nextdaymobilestudio.lebenindeutschland.core.preferences.OnboardingPreferencesRepository
import com.nextdaymobilestudio.lebenindeutschland.feature.languageselection.model.SupportedLanguage
import com.nextdaymobilestudio.lebenindeutschland.feature.mockexam.data.MockExamPreferencesRepository

/**
 * Switches the study language from Settings.
 *
 * The new language is committed only once its content is installed. Committing it
 * first would leave an offline learner on a bootstrap error they cannot leave, on
 * every launch, while their current language is still fully installed.
 */
internal class SettingsLanguageChange(
    private val backStack: MutableList<NavKey>,
    private val onboardingPreferencesRepository: OnboardingPreferencesRepository,
    private val mockExamPreferencesRepository: MockExamPreferencesRepository,
    private val onApplicationLanguageSelected: (SupportedLanguage) -> Unit,
    private val navigateBack: () -> Unit,
) {
    suspend fun request(
        language: SupportedLanguage,
        persistedLanguage: SupportedLanguage?,
    ) {
        if (language.code == persistedLanguage?.code) {
            navigateBack()
        } else {
            val download = SettingsLanguageDownloadKey(language.code)
            if (backStack.lastOrNull() != download) {
                backStack.add(download)
            }
        }
    }

    suspend fun complete(
        download: SettingsLanguageDownloadKey,
        questionCount: Int,
    ) {
        val language = SupportedLanguage.fromCode(download.languageCode) ?: return
        mockExamPreferencesRepository.clearExam()
        onboardingPreferencesRepository.setLanguageCode(language.code)
        backStack.clear()
        backStack.add(HomeKey(questionCount))
        onApplicationLanguageSelected(language)
    }
}
