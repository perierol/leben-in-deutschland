package com.nextdaymobilestudio.lebenindeutschland.core.content.sync

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.nextdaymobilestudio.lebenindeutschland.core.content.remote.FirebaseAnonymousAuthenticator
import com.nextdaymobilestudio.lebenindeutschland.core.content.remote.FirestoreContentDataSource
import com.nextdaymobilestudio.lebenindeutschland.core.database.repository.LocalContentRepository

object FirebaseContentSynchronizerFactory {
    const val EXPECTED_PROJECT_ID = "leben-in-deutschland-38a07"

    fun create(
        context: Context,
        localContentRepository: LocalContentRepository,
    ): ContentSynchronizer {
        val firebaseApp = existingOrInitializeFirebaseApp(context)
        requireExpectedFirebaseProject(firebaseApp.options.projectId)

        return ContentSyncCoordinator(
            remoteContentDataSource = FirestoreContentDataSource(
                firestore = FirebaseFirestore.getInstance(firebaseApp),
            ),
            localContentRepository = localContentRepository,
            contentAuthenticator = FirebaseAnonymousAuthenticator(
                firebaseAuth = FirebaseAuth.getInstance(firebaseApp),
            ),
        )
    }

    internal fun requireExpectedFirebaseProject(projectId: String?): String {
        if (projectId != EXPECTED_PROJECT_ID) {
            throw ContentSyncException(
                "Refusing Firebase project ${projectId ?: "<missing>"}; " +
                    "expected $EXPECTED_PROJECT_ID",
            )
        }
        return projectId
    }

    private fun existingOrInitializeFirebaseApp(context: Context): FirebaseApp =
        runCatching { FirebaseApp.getInstance() }.getOrNull()
            ?: FirebaseApp.initializeApp(context.applicationContext)
            ?: throw ContentSyncException(
                "Firebase is not configured. Add google-services.json to the app module.",
            )
}
