package com.nextdaymobilestudio.lebenindeutschland.core.content.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundContentRefresherTest {
    private val reports = mutableListOf<Pair<String, Throwable>>()

    @Test
    fun stalledRefreshEndsAtTheTimeoutAndIsReported() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val refresher = BackgroundContentRefresher(
            scope = backgroundScope,
            contentSynchronizerProvider = {
                ContentSynchronizer {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            },
            timeoutMillis = TIMEOUT_MILLIS,
            reportError = { message, throwable -> reports += message to throwable },
        )

        refresher.start("en")
        advanceTimeBy(TIMEOUT_MILLIS - 1)
        runCurrent()
        assertTrue("Still running before the timeout", reports.isEmpty())

        advanceTimeBy(2)
        runCurrent()

        assertTrue("The stalled sync was cancelled", cancelled.isCompleted)
        val (message, throwable) = reports.single()
        assertEquals("Content refresh timed out for language en", message)
        assertTrue(throwable is TimeoutCancellationException)
    }

    @Test
    fun failedRefreshIsReportedAndDoesNotEscape() = runTest {
        val refresher = BackgroundContentRefresher(
            scope = backgroundScope,
            contentSynchronizerProvider = {
                ContentSynchronizer { throw ContentSyncException("Server unavailable") }
            },
            reportError = { message, throwable -> reports += message to throwable },
        )

        refresher.start("tr")
        runCurrent()

        // Compared by type and message: coroutine stack-trace recovery may rethrow a copy.
        val (message, throwable) = reports.single()
        assertEquals("Content refresh failed for language tr", message)
        assertTrue(throwable is ContentSyncException)
        assertEquals("Server unavailable", throwable.message)
    }

    @Test
    fun cancelAndJoinStopsTheRunningRefreshBeforeReturning() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val refresher = BackgroundContentRefresher(
            scope = backgroundScope,
            contentSynchronizerProvider = {
                ContentSynchronizer {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            },
            reportError = { message, throwable -> reports += message to throwable },
        )
        refresher.start("en")
        runCurrent()

        refresher.cancelAndJoin()

        assertTrue(cancelled.isCompleted)
        assertTrue("Cancellation is not a failure", reports.isEmpty())
    }

    @Test
    fun startingAnotherRefreshStopsTheEarlierOne() = runTest {
        val started = mutableListOf<String>()
        val cancelledLanguages = mutableListOf<String>()
        val refresher = BackgroundContentRefresher(
            scope = backgroundScope,
            contentSynchronizerProvider = {
                ContentSynchronizer { languageCode ->
                    started += languageCode
                    try {
                        awaitCancellation()
                    } finally {
                        cancelledLanguages += languageCode
                    }
                }
            },
        )

        refresher.start("en")
        runCurrent()
        refresher.start("tr")
        runCurrent()

        assertEquals(listOf("en", "tr"), started)
        assertEquals(listOf("en"), cancelledLanguages)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
