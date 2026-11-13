package com.nextdaymobilestudio.lebenindeutschland.feature.settings.ui

import androidx.compose.runtime.Composable

@Composable
fun SettingsRoute(
    languageName: String,
    stateName: String,
    onBack: () -> Unit,
    onChangeLanguage: () -> Unit,
    onChangeState: () -> Unit,
    showPrivacyOptions: Boolean = false,
    onOpenPrivacyOptions: () -> Unit = {},
) {
    SettingsScreen(
        languageName = languageName,
        stateName = stateName,
        onBack = onBack,
        onChangeLanguage = onChangeLanguage,
        onChangeState = onChangeState,
        showPrivacyOptions = showPrivacyOptions,
        onOpenPrivacyOptions = onOpenPrivacyOptions,
    )
}
