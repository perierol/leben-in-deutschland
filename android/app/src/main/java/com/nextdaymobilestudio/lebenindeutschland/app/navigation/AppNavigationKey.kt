package com.nextdaymobilestudio.lebenindeutschland.app.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppNavigationKey : NavKey

@Serializable
data object LanguageSelectionKey : AppNavigationKey

@Serializable
data object StateSelectionKey : AppNavigationKey

@Serializable
data object ContentBootstrapKey : AppNavigationKey

@Serializable
data class HomeKey(
    val questionCount: Int,
) : AppNavigationKey

@Serializable
data class StudySessionKey(
    val wrongAnswersOnly: Boolean = false,
) : AppNavigationKey

@Serializable
data object MockExamKey : AppNavigationKey

@Serializable
data object ExamHistoryKey : AppNavigationKey

@Serializable
data class ExamHistoryDetailKey(
    val attemptId: String,
) : AppNavigationKey

@Serializable
data object SettingsKey : AppNavigationKey

@Serializable
data object SettingsLanguageKey : AppNavigationKey

@Serializable
data object SettingsStateKey : AppNavigationKey

@Serializable
data class SettingsLanguageDownloadKey(
    val languageCode: String,
) : AppNavigationKey
