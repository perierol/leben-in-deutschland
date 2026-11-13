package com.nextdaymobilestudio.lebenindeutschland.feature.contentbootstrap

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.BackgroundContentRefresher
import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.ContentSynchronizer
import com.nextdaymobilestudio.lebenindeutschland.testing.MainDispatcherRule
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ContentBootstrapViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val viewModelStore = ViewModelStore()

    @Test
    fun installedContentLetsLearnerEnterWhileServerRefreshNeverCompletes() = runViewModelTest {
        val local = FakeLocalContentRepository().apply { seedUsableContent("en") }
        val refreshedLanguages = mutableListOf<String>()
        val neverCompletingSynchronizer = ContentSynchronizer { languageCode ->
            refreshedLanguages += languageCode
            // A server that never answers: a captive portal or a stalled connection.
            awaitCancellation()
        }
        val coordinator = ContentBootstrapCoordinator(
            localContentRepository = local,
            contentSynchronizerProvider = { neverCompletingSynchronizer },
            contentRefresher = BackgroundContentRefresher(
                scope = backgroundScope,
                contentSynchronizerProvider = { neverCompletingSynchronizer },
            ),
        )

        val viewModel = createViewModel(coordinator)
        runCurrent()

        assertLearnerCanEnterApp(viewModel.uiState.value)
        assertEquals("The server refresh still runs", listOf("en"), refreshedLanguages)
    }

    private fun runViewModelTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            viewModelStore.clear()
        }
    }

    private fun createViewModel(
        coordinator: ContentBootstrapCoordinator,
    ): ContentBootstrapViewModel = ViewModelProvider.create(
        store = viewModelStore,
        factory = viewModelFactory {
            initializer {
                ContentBootstrapViewModel(
                    selectedLanguageCode = "en",
                    coordinator = coordinator,
                )
            }
        },
    )[ContentBootstrapViewModel::class]

    private companion object {
        /** The condition ContentBootstrapRoute uses to leave the bootstrap screen. */
        fun assertLearnerCanEnterApp(state: ContentBootstrapUiState) {
            assertTrue(
                "Expected a Ready state that opens Home, but was $state",
                state is ContentBootstrapUiState.Ready && !state.isRefreshing,
            )
        }
    }
}
