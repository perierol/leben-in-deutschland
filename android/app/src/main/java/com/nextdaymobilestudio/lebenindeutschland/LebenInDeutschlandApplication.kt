package com.nextdaymobilestudio.lebenindeutschland

import android.app.Application
import android.content.Context
import android.util.Log
import com.nextdaymobilestudio.lebenindeutschland.core.ads.AdMobBannerAdFactory
import com.nextdaymobilestudio.lebenindeutschland.core.ads.AdMobIds
import com.nextdaymobilestudio.lebenindeutschland.core.ads.AdsController
import com.nextdaymobilestudio.lebenindeutschland.core.ads.BannerAdFactory
import com.nextdaymobilestudio.lebenindeutschland.core.ads.ConsentAwareAdsController
import com.nextdaymobilestudio.lebenindeutschland.core.ads.GoogleMobileAdsInitializer
import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.BackgroundContentRefresher
import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.ContentRefresher
import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.ContentSynchronizer
import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.FirebaseContentSynchronizerFactory
import com.nextdaymobilestudio.lebenindeutschland.core.database.LocalContentDatabase
import com.nextdaymobilestudio.lebenindeutschland.core.database.repository.LocalContentRepository
import com.nextdaymobilestudio.lebenindeutschland.core.database.repository.RoomLocalContentRepository
import com.nextdaymobilestudio.lebenindeutschland.core.preferences.OnboardingPreferencesRepository
import com.nextdaymobilestudio.lebenindeutschland.core.preferences.createOnboardingPreferencesRepository
import com.nextdaymobilestudio.lebenindeutschland.feature.mockexam.data.MockExamPreferencesRepository
import com.nextdaymobilestudio.lebenindeutschland.feature.mockexam.data.createMockExamPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

open class LebenInDeutschlandApplication : Application() {
    /** Instrumented tests override this, so no test requests ads or consent. */
    protected open val adsEnabled: Boolean = true

    val appContainer: AppContainer by lazy {
        AppContainer(applicationContext, adsEnabled = adsEnabled)
    }
}

class AppContainer(
    context: Context,
    adsEnabled: Boolean = true,
) {
    val onboardingPreferencesRepository: OnboardingPreferencesRepository =
        context.createOnboardingPreferencesRepository()

    val mockExamPreferencesRepository: MockExamPreferencesRepository =
        context.createMockExamPreferencesRepository()

    private val localContentDatabase: LocalContentDatabase =
        LocalContentDatabase.create(context)

    val localContentRepository: LocalContentRepository =
        RoomLocalContentRepository(localContentDatabase)

    val contentSynchronizerProvider: () -> ContentSynchronizer = {
        FirebaseContentSynchronizerFactory.create(
            context = context,
            localContentRepository = localContentRepository,
        )
    }

    // Application-wide, so a refresh outlives the bootstrap screen and the activity
    // recreation that follows a language change.
    val contentRefresher: ContentRefresher = BackgroundContentRefresher(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        contentSynchronizerProvider = contentSynchronizerProvider,
        reportError = { message, throwable ->
            Log.w(CONTENT_REFRESH_LOG_TAG, message, throwable)
        },
    )

    // Null turns ads off: disabled, or no usable ID for this build (a test ID outside
    // debug, for example). Ads never affect content or study features.
    private val questionDetailBannerId: String? = if (adsEnabled) {
        AdMobIds.usableAdUnitId(
            adUnitId = BuildConfig.ADMOB_QUESTION_DETAIL_BANNER_ID,
            isDebugBuild = BuildConfig.DEBUG,
        )
    } else {
        null
    }

    val adsController: AdsController = if (questionDetailBannerId != null) {
        ConsentAwareAdsController(
            mobileAdsInitializer = GoogleMobileAdsInitializer(context),
            reportError = { message, throwable -> Log.w(ADS_LOG_TAG, message, throwable) },
        )
    } else {
        AdsController.Disabled
    }

    val questionBannerAdFactory: BannerAdFactory? =
        questionDetailBannerId?.let(::AdMobBannerAdFactory)

    private companion object {
        const val CONTENT_REFRESH_LOG_TAG = "ContentRefresh"
        const val ADS_LOG_TAG = "Ads"
    }
}
