package com.nextdaymobilestudio.lebenindeutschland

import android.app.Application
import android.content.Context
import android.util.Log
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

class LebenInDeutschlandApplication : Application() {
    val appContainer: AppContainer by lazy {
        AppContainer(applicationContext)
    }
}

class AppContainer(
    context: Context,
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

    private companion object {
        const val CONTENT_REFRESH_LOG_TAG = "ContentRefresh"
    }
}
