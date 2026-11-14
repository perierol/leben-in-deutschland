package com.nextdaymobilestudio.lebenindeutschland

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.nextdaymobilestudio.lebenindeutschland.app.LebenInDeutschlandApp
import com.nextdaymobilestudio.lebenindeutschland.feature.languageselection.model.SupportedLanguage
import com.nextdaymobilestudio.lebenindeutschland.ui.theme.LebenInDeutschlandTheme

class MainActivity : AppCompatActivity() {
    private val appContainer: AppContainer
        get() = (application as LebenInDeutschlandApplication).appContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            LebenInDeutschlandTheme {
                LebenInDeutschlandApp(
                    onboardingPreferencesRepository =
                        appContainer.onboardingPreferencesRepository,
                    localContentRepository = appContainer.localContentRepository,
                    mockExamPreferencesRepository =
                        appContainer.mockExamPreferencesRepository,
                    contentSynchronizerProvider =
                        appContainer.contentSynchronizerProvider,
                    contentRefresher = appContainer.contentRefresher,
                    adsController = appContainer.adsController,
                    questionBannerAdFactory = appContainer.questionBannerAdFactory,
                    onApplicationLanguageSelected = ::applyApplicationLanguage,
                )
            }
        }
    }

    private fun applyApplicationLanguage(language: SupportedLanguage) {
        val requestedLocales = LocaleListCompat.forLanguageTags(language.code)
        val currentLocales = AppCompatDelegate.getApplicationLocales()

        if (currentLocales.toLanguageTags() != requestedLocales.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(requestedLocales)
        }
    }
}
