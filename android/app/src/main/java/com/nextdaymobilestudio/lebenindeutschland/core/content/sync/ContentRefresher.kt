package com.nextdaymobilestudio.lebenindeutschland.core.content.sync

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Checks the server for newer content without holding up the learner (ADR 0006). */
interface ContentRefresher {
    /** Starts a background refresh for [languageCode], replacing any earlier one. */
    suspend fun start(languageCode: String)

    /** Stops a running refresh and waits until it can no longer change local content. */
    suspend fun cancelAndJoin()

    companion object {
        /** Refreshes nothing. Used by tests that do not exercise refreshes. */
        val None: ContentRefresher = object : ContentRefresher {
            override suspend fun start(languageCode: String) = Unit

            override suspend fun cancelAndJoin() = Unit
        }
    }
}

/**
 * Runs the server refresh in [scope], outside any screen, so a learner with installed
 * content studies immediately while the refresh continues.
 *
 * A refresh ends after [timeoutMillis] at the latest, so a stalled server cannot keep
 * one running indefinitely. The synchronizer still validates and installs a release
 * completely before activating it, so a refresh that is cancelled or times out
 * leaves the current content in place.
 */
class BackgroundContentRefresher(
    private val scope: CoroutineScope,
    private val contentSynchronizerProvider: () -> ContentSynchronizer,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val reportError: (String, Throwable) -> Unit = { _, _ -> },
) : ContentRefresher {
    private val lock = Mutex()
    private var refreshJob: Job? = null

    override suspend fun start(languageCode: String) = lock.withLock {
        refreshJob?.cancelAndJoin()
        refreshJob = scope.launch { refresh(languageCode) }
    }

    override suspend fun cancelAndJoin() = lock.withLock {
        refreshJob?.cancelAndJoin()
        refreshJob = null
    }

    private suspend fun refresh(languageCode: String) {
        try {
            withTimeout(timeoutMillis) {
                contentSynchronizerProvider().synchronize(languageCode)
            }
        } catch (exception: TimeoutCancellationException) {
            reportError("Content refresh timed out for language $languageCode", exception)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            reportError("Content refresh failed for language $languageCode", exception)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
    }
}
