package com.nextdaymobilestudio.lebenindeutschland.app

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.AppNavigationKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.ContentBootstrapKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.ExamHistoryDetailKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.ExamHistoryKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.HomeKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.LanguageSelectionKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.MockExamKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.SettingsKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.SettingsLanguageDownloadKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.SettingsLanguageKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.SettingsStateKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.StateSelectionKey
import com.nextdaymobilestudio.lebenindeutschland.app.navigation.StudySessionKey
import com.nextdaymobilestudio.lebenindeutschland.core.content.sync.ContentSynchronizer
import com.nextdaymobilestudio.lebenindeutschland.core.database.repository.LocalContentRepository
import com.nextdaymobilestudio.lebenindeutschland.core.preferences.OnboardingPreferences
import com.nextdaymobilestudio.lebenindeutschland.core.preferences.OnboardingPreferencesRepository
import com.nextdaymobilestudio.lebenindeutschland.feature.mockexam.data.MockExamPreferencesRepository
import com.nextdaymobilestudio.lebenindeutschland.feature.mockexam.ui.MockExamRoute
import com.nextdaymobilestudio.lebenindeutschland.feature.contentbootstrap.ContentBootstrapCoordinator
import com.nextdaymobilestudio.lebenindeutschland.feature.contentbootstrap.ui.ContentBootstrapRoute
import com.nextdaymobilestudio.lebenindeutschland.feature.examhistory.ui.ExamHistoryDetailRoute
import com.nextdaymobilestudio.lebenindeutschland.feature.examhistory.ui.ExamHistoryRoute
import com.nextdaymobilestudio.lebenindeutschland.feature.home.ui.HomeRoute
import com.nextdaymobilestudio.lebenindeutschland.feature.languageselection.model.SupportedLanguage
import com.nextdaymobilestudio.lebenindeutschland.feature.languageselection.ui.LanguageSelectionRoute
import com.nextdaymobilestudio.lebenindeutschland.feature.settings.ui.SettingsRoute
import com.nextdaymobilestudio.lebenindeutschland.feature.stateselection.model.GermanState
import com.nextdaymobilestudio.lebenindeutschland.feature.stateselection.ui.StateSelectionRoute
import com.nextdaymobilestudio.lebenindeutschland.feature.studysession.ui.StudySessionRoute
import com.nextdaymobilestudio.lebenindeutschland.feature.studysession.StudySessionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun LebenInDeutschlandApp(
    onboardingPreferencesRepository: OnboardingPreferencesRepository,
    localContentRepository: LocalContentRepository,
    mockExamPreferencesRepository: MockExamPreferencesRepository,
    contentSynchronizerProvider: () -> ContentSynchronizer,
    onApplicationLanguageSelected: (SupportedLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nullablePreferences = remember(onboardingPreferencesRepository) {
        onboardingPreferencesRepository.preferences.asNullable()
    }
    val preferences by nullablePreferences.collectAsStateWithLifecycle(initialValue = null)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val loadedPreferences = preferences
        if (loadedPreferences == null) {
            CircularProgressIndicator()
        } else {
            OnboardingNavigation(
                preferences = loadedPreferences,
                repository = onboardingPreferencesRepository,
                localContentRepository = localContentRepository,
                mockExamPreferencesRepository = mockExamPreferencesRepository,
                contentSynchronizerProvider = contentSynchronizerProvider,
                onApplicationLanguageSelected = onApplicationLanguageSelected,
            )
        }
    }
}

@Composable
private fun OnboardingNavigation(
    preferences: OnboardingPreferences,
    repository: OnboardingPreferencesRepository,
    localContentRepository: LocalContentRepository,
    mockExamPreferencesRepository: MockExamPreferencesRepository,
    contentSynchronizerProvider: () -> ContentSynchronizer,
    onApplicationLanguageSelected: (SupportedLanguage) -> Unit,
) {
    val persistedLanguage = SupportedLanguage.fromCode(preferences.languageCode)
    val persistedState = GermanState.fromCode(preferences.stateCode)
    val initialDestination: AppNavigationKey = remember {
        when {
            persistedLanguage == null -> LanguageSelectionKey
            persistedState == null -> StateSelectionKey
            else -> ContentBootstrapKey
        }
    }
    val backStack = rememberNavBackStack(initialDestination)
    val coroutineScope = rememberCoroutineScope()
    val contentBootstrapCoordinator = remember(
        localContentRepository,
        contentSynchronizerProvider,
    ) {
        ContentBootstrapCoordinator(
            localContentRepository = localContentRepository,
            contentSynchronizerProvider = contentSynchronizerProvider,
            reportError = { message, throwable ->
                Log.e(CONTENT_BOOTSTRAP_LOG_TAG, message, throwable)
            },
        )
    }
    val navigateBack: () -> Unit = {
        when {
            backStack.size > 1 -> backStack.removeLastOrNull()
            backStack.lastOrNull() == StateSelectionKey -> {
                backStack.removeLastOrNull()
                backStack.add(LanguageSelectionKey)
            }
        }
    }
    val settingsLanguageChange = SettingsLanguageChange(
        backStack = backStack,
        onboardingPreferencesRepository = repository,
        mockExamPreferencesRepository = mockExamPreferencesRepository,
        onApplicationLanguageSelected = onApplicationLanguageSelected,
        navigateBack = navigateBack,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            onBack = navigateBack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<LanguageSelectionKey> {
                    LanguageSelectionRoute(
                        persistedLanguage = persistedLanguage,
                        onContinue = { language ->
                            coroutineScope.launch {
                                repository.setLanguageCode(language.code)
                                if (backStack.lastOrNull() != StateSelectionKey) {
                                    backStack.add(StateSelectionKey)
                                }
                                onApplicationLanguageSelected(language)
                            }
                        },
                    )
                }
                entry<StateSelectionKey> {
                    StateSelectionRoute(
                        initialState = persistedState,
                        onBack = navigateBack,
                        onContinue = { state ->
                            coroutineScope.launch {
                                repository.setStateCode(state.code)
                                if (backStack.lastOrNull() != ContentBootstrapKey) {
                                    backStack.add(ContentBootstrapKey)
                                }
                            }
                        },
                    )
                }
                entry<ContentBootstrapKey> {
                    ContentBootstrapRoute(
                        selectedLanguageCode = persistedLanguage?.code
                            ?: SupportedLanguage.GERMAN.code,
                        coordinator = contentBootstrapCoordinator,
                        onContentReady = { questionCount ->
                            backStack.removeLastOrNull()
                            backStack.add(HomeKey(questionCount))
                        },
                    )
                }
                entry<HomeKey> {
                    HomeRoute(
                        languageName = persistedLanguage?.nativeName.orEmpty(),
                        stateName = persistedState?.germanName.orEmpty(),
                        selectedLanguageCode = persistedLanguage?.code
                            ?: SupportedLanguage.GERMAN.code,
                        selectedStateCode = persistedState?.code,
                        localContentRepository = localContentRepository,
                        mockExamPreferencesRepository = mockExamPreferencesRepository,
                        onStartStudy = {
                            val destination = StudySessionKey()
                            if (backStack.lastOrNull() != destination) {
                                backStack.add(destination)
                            }
                        },
                        onReviewWrongAnswers = {
                            val destination = StudySessionKey(wrongAnswersOnly = true)
                            if (backStack.lastOrNull() != destination) {
                                backStack.add(destination)
                            }
                        },
                        onOpenMockExam = {
                            if (backStack.lastOrNull() != MockExamKey) {
                                backStack.add(MockExamKey)
                            }
                        },
                        onOpenExamHistory = {
                            if (backStack.lastOrNull() != ExamHistoryKey) {
                                backStack.add(ExamHistoryKey)
                            }
                        },
                        onOpenSettings = {
                            if (backStack.lastOrNull() != SettingsKey) {
                                backStack.add(SettingsKey)
                            }
                        },
                    )
                }
                entry<StudySessionKey> { key ->
                    StudySessionRoute(
                        selectedLanguageCode = persistedLanguage?.code
                            ?: SupportedLanguage.GERMAN.code,
                        selectedStateCode = persistedState?.code,
                        localContentRepository = localContentRepository,
                        mode = if (key.wrongAnswersOnly) {
                            StudySessionMode.WRONG_ANSWERS
                        } else {
                            StudySessionMode.ALL_QUESTIONS
                        },
                        onBack = navigateBack,
                        onFinished = navigateBack,
                    )
                }
                entry<MockExamKey> {
                    MockExamRoute(
                        selectedLanguageCode = persistedLanguage?.code
                            ?: SupportedLanguage.GERMAN.code,
                        selectedStateCode = persistedState?.code,
                        localContentRepository = localContentRepository,
                        mockExamPreferencesRepository = mockExamPreferencesRepository,
                        onBack = navigateBack,
                        onFinished = navigateBack,
                    )
                }
                entry<ExamHistoryKey> {
                    ExamHistoryRoute(
                        localContentRepository = localContentRepository,
                        onBack = navigateBack,
                        onOpenAttempt = { attemptId ->
                            val destination = ExamHistoryDetailKey(attemptId)
                            if (backStack.lastOrNull() != destination) {
                                backStack.add(destination)
                            }
                        },
                    )
                }
                entry<ExamHistoryDetailKey> { key ->
                    ExamHistoryDetailRoute(
                        attemptId = key.attemptId,
                        localContentRepository = localContentRepository,
                        onBack = navigateBack,
                    )
                }
                entry<SettingsKey> {
                    SettingsRoute(
                        languageName = persistedLanguage?.nativeName.orEmpty(),
                        stateName = persistedState?.germanName.orEmpty(),
                        onBack = navigateBack,
                        onChangeLanguage = {
                            if (backStack.lastOrNull() != SettingsLanguageKey) {
                                backStack.add(SettingsLanguageKey)
                            }
                        },
                        onChangeState = {
                            if (backStack.lastOrNull() != SettingsStateKey) {
                                backStack.add(SettingsStateKey)
                            }
                        },
                    )
                }
                entry<SettingsLanguageKey> {
                    LanguageSelectionRoute(
                        persistedLanguage = persistedLanguage,
                        onBack = navigateBack,
                        onContinue = { language ->
                            coroutineScope.launch {
                                settingsLanguageChange.request(language, persistedLanguage)
                            }
                        },
                    )
                }
                entry<SettingsLanguageDownloadKey> { key ->
                    ContentBootstrapRoute(
                        selectedLanguageCode = key.languageCode,
                        coordinator = contentBootstrapCoordinator,
                        onContentReady = { questionCount ->
                            coroutineScope.launch {
                                settingsLanguageChange.complete(key, questionCount)
                            }
                        },
                    )
                }
                entry<SettingsStateKey> {
                    StateSelectionRoute(
                        initialState = persistedState,
                        onBack = navigateBack,
                        onContinue = { state ->
                            coroutineScope.launch {
                                if (state.code != persistedState?.code) {
                                    mockExamPreferencesRepository.clearExam()
                                    repository.setStateCode(state.code)
                                }
                                navigateBack()
                            }
                        },
                    )
                }
            },
        )
    }
}

private fun Flow<OnboardingPreferences>.asNullable(): Flow<OnboardingPreferences?> =
    map { preferences -> preferences }

private const val CONTENT_BOOTSTRAP_LOG_TAG = "ContentBootstrap"
