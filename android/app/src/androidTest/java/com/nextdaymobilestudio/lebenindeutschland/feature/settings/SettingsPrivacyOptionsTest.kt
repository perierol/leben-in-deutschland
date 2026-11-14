package com.nextdaymobilestudio.lebenindeutschland.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nextdaymobilestudio.lebenindeutschland.feature.settings.ui.SettingsScreen
import com.nextdaymobilestudio.lebenindeutschland.feature.settings.ui.SettingsTestTags
import com.nextdaymobilestudio.lebenindeutschland.ui.theme.LebenInDeutschlandTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPrivacyOptionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun privacyOptionsAppearWhenRequiredAndOpenTheForm() {
        var opened = 0
        setScreen(showPrivacyOptions = true, onOpenPrivacyOptions = { opened++ })

        composeRule.onNodeWithTag(SettingsTestTags.PRIVACY_OPTIONS)
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, opened)
    }

    @Test
    fun privacyOptionsAreHiddenWhenNotRequired() {
        setScreen(showPrivacyOptions = false)

        composeRule.onNodeWithTag(SettingsTestTags.PRIVACY_OPTIONS).assertDoesNotExist()
    }

    private fun setScreen(
        showPrivacyOptions: Boolean,
        onOpenPrivacyOptions: () -> Unit = {},
    ) {
        composeRule.setContent {
            LebenInDeutschlandTheme {
                SettingsScreen(
                    languageName = "Deutsch",
                    stateName = "Berlin",
                    onBack = {},
                    onChangeLanguage = {},
                    onChangeState = {},
                    showPrivacyOptions = showPrivacyOptions,
                    onOpenPrivacyOptions = onOpenPrivacyOptions,
                )
            }
        }
    }
}
