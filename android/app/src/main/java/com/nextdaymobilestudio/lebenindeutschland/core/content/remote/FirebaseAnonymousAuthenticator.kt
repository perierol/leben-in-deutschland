package com.nextdaymobilestudio.lebenindeutschland.core.content.remote

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.ContentSyncException
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Signs in anonymously when no Firebase session exists yet.
 *
 * Firebase persists the anonymous account across launches, so this is a no-op on
 * every run after the first.
 */
class FirebaseAnonymousAuthenticator(
    private val firebaseAuth: FirebaseAuth,
) : ContentAuthenticator {
    override suspend fun ensureAuthenticated() {
        if (firebaseAuth.currentUser != null) return

        val result = runCatching { firebaseAuth.signInAnonymously().awaitResult() }
            .getOrElse { cause ->
                if (cause is CancellationException) throw cause
                throw ContentSyncException(
                    "Anonymous Firebase sign-in failed: ${cause.message ?: "unknown error"}",
                )
            }

        if (result.user == null) {
            throw ContentSyncException("Anonymous Firebase sign-in returned no user")
        }
    }

    private suspend fun <T : Any> Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { completedTask ->
                if (!continuation.isActive) return@addOnCompleteListener

                when {
                    completedTask.isCanceled -> continuation.resumeWithException(
                        CancellationException("Firebase sign-in was cancelled"),
                    )
                    completedTask.exception != null -> continuation.resumeWithException(
                        completedTask.exception
                            ?: ContentSyncException("Firebase sign-in failed"),
                    )
                    else -> {
                        val result = completedTask.result
                        if (result != null) {
                            continuation.resume(result)
                        } else {
                            continuation.resumeWithException(
                                ContentSyncException("Firebase sign-in returned no result"),
                            )
                        }
                    }
                }
            }
        }
}
