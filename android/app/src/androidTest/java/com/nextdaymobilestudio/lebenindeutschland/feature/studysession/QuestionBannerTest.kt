package com.nextdaymobilestudio.lebenindeutschland.feature.studysession

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nextdaymobilestudio.lebenindeutschland.core.ads.AdsAvailability
import com.nextdaymobilestudio.lebenindeutschland.core.ads.AdsController
import com.nextdaymobilestudio.lebenindeutschland.core.ads.BannerAd
import com.nextdaymobilestudio.lebenindeutschland.core.ads.BannerAdFactory
import com.nextdaymobilestudio.lebenindeutschland.core.ads.ConsentGateway
import com.nextdaymobilestudio.lebenindeutschland.core.ads.QuestionBannerLayout
import com.nextdaymobilestudio.lebenindeutschland.core.ads.QuestionBannerSlot
import com.nextdaymobilestudio.lebenindeutschland.core.ads.QuestionBannerTestTags
import com.nextdaymobilestudio.lebenindeutschland.core.ads.rememberQuestionBanner
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyAnswer
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyQuestion
import com.nextdaymobilestudio.lebenindeutschland.feature.studysession.ui.StudySessionScreen
import com.nextdaymobilestudio.lebenindeutschland.feature.studysession.ui.StudySessionTestTags
import com.nextdaymobilestudio.lebenindeutschland.ui.theme.LebenInDeutschlandTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Banner behaviour on practice questions, with a fake banner that makes no ad requests. */
@RunWith(AndroidJUnit4::class)
class QuestionBannerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val factory = FakeBannerAdFactory()
    private val adsController = FakeAdsController(AdsAvailability.AVAILABLE)
    private var windowHeightDp = 0

    @Test
    fun bannerSitsUnderTheTopBarAndAwayFromQuestionAndActionButton() {
        setScreen(mutableStateOf(questionState(index = 0, selectedAnswerId = "A")))

        val banner = bounds(QuestionBannerTestTags.SLOT)
        val question = bounds(StudySessionTestTags.QUESTION)
        val action = bounds(StudySessionTestTags.ACTION)
        assertTrue("Banner overlaps the question", banner.bottom <= question.top)
        assertTrue("Banner reaches the action button", banner.bottom < action.top)
        composeRule.onNodeWithTag(QuestionBannerTestTags.SLOT)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 1f))
    }

    @Test
    fun checkNextAndTheLoadingStepKeepTheSameBannerWithoutReloading() {
        val state = mutableStateOf<StudySessionUiState>(questionState(index = 0))
        setScreen(state)

        state.value = questionState(index = 0, selectedAnswerId = "A", isSubmitted = true)
        composeRule.waitForIdle()
        state.value = StudySessionUiState.Loading
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(QuestionBannerTestTags.SLOT).assertIsDisplayed()
        state.value = questionState(index = 1, questionId = "general_002")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(QuestionBannerTestTags.SLOT).assertIsDisplayed()
        assertEquals(1, factory.created.size)
        assertEquals(1, factory.created.single().loads)
        assertEquals(0, factory.created.single().destroys)
    }

    @Test
    fun completedAndEmptySessionsShowNoBanner() {
        val state = mutableStateOf<StudySessionUiState>(
            StudySessionUiState.Complete(questionCount = 2, correctCount = 1),
        )
        setScreen(state)
        composeRule.onNodeWithTag(QuestionBannerTestTags.SLOT).assertDoesNotExist()

        state.value = StudySessionUiState.EmptyWrongAnswers
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(QuestionBannerTestTags.SLOT).assertDoesNotExist()
    }

    @Test
    fun unavailableAdsCreateNoBanner() {
        adsController.availability.value = AdsAvailability.UNAVAILABLE
        setScreen(mutableStateOf(questionState(index = 0)))

        composeRule.onNodeWithTag(QuestionBannerTestTags.SLOT).assertDoesNotExist()
        assertTrue(factory.created.isEmpty())
    }

    @Test
    fun adsThatBecomeAvailableMidQuestionDoNotPushTheContentDown() {
        adsController.availability.value = AdsAvailability.UNKNOWN
        setScreen(mutableStateOf(questionState(index = 0)))

        adsController.availability.value = AdsAvailability.AVAILABLE
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(QuestionBannerTestTags.SLOT).assertDoesNotExist()
        assertTrue(factory.created.isEmpty())
    }

    @Test
    fun withdrawnConsentRemovesAndDestroysTheBanner() {
        setScreen(mutableStateOf(questionState(index = 0)))

        adsController.availability.value = AdsAvailability.UNAVAILABLE
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(QuestionBannerTestTags.SLOT).assertDoesNotExist()
        assertEquals(1, factory.created.single().destroys)
    }

    @Test
    fun failedLoadKeepsItsSpaceUntilTheNextQuestion() {
        val state = mutableStateOf<StudySessionUiState>(questionState(index = 0))
        setScreen(state)

        composeRule.runOnIdle { factory.created.single().failLoad() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(QuestionBannerTestTags.SLOT).assertIsDisplayed()

        state.value = questionState(index = 1, questionId = "general_002")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(QuestionBannerTestTags.SLOT).assertDoesNotExist()
    }

    @Test
    fun rightToLeftWithLargeTextKeepsTheBannerSeparate() {
        val fontScale = 1.3f
        setScreen(
            mutableStateOf(questionState(index = 0, selectedAnswerId = "A")),
            layoutDirection = LayoutDirection.Rtl,
            fontScale = fontScale,
        )
        assumeTrue(
            "The test device is too short for the banner at this text size",
            screenFits(fontScale),
        )

        val banner = bounds(QuestionBannerTestTags.SLOT)
        val question = bounds(StudySessionTestTags.QUESTION)
        val action = bounds(StudySessionTestTags.ACTION)
        assertTrue("Banner overlaps the question", banner.bottom <= question.top)
        assertTrue("Banner reaches the action button", banner.bottom < action.top)
    }

    private fun setScreen(
        state: MutableState<StudySessionUiState>,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float? = null,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            windowHeightDp = with(density) {
                LocalWindowInfo.current.containerSize.height.toDp().value.toInt()
            }
            CompositionLocalProvider(
                LocalLayoutDirection provides layoutDirection,
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
            ) {
                LebenInDeutschlandTheme {
                    PracticeScreenWithBanner(state.value)
                }
            }
        }
    }

    /** Mirrors StudySessionRoute: the banner is held outside the question content. */
    @Composable
    private fun PracticeScreenWithBanner(uiState: StudySessionUiState) {
        val banner = rememberQuestionBanner(adsController, factory)
        StudySessionScreen(
            uiState = uiState,
            onBack = {},
            onAnswerSelected = {},
            onSubmitAnswer = {},
            onNextQuestion = {},
            onRetry = {},
            onFinished = {},
            adBanner = banner?.let { questionBanner ->
                {
                    QuestionBannerSlot(
                        banner = questionBanner,
                        collapseKey = (uiState as? StudySessionUiState.Ready)?.currentIndex,
                    )
                }
            },
        )
    }

    private fun bounds(tag: String): DpRect =
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()

    private fun screenFits(fontScale: Float): Boolean =
        QuestionBannerLayout.fitsScreen(windowHeightDp, fontScale)
}

private class FakeAdsController(initial: AdsAvailability) : AdsController {
    override val availability = MutableStateFlow(initial)
    override val isPrivacyOptionsRequired = MutableStateFlow(false)

    override suspend fun gatherConsent(consentGateway: ConsentGateway) = Unit

    override suspend fun showPrivacyOptions(consentGateway: ConsentGateway) = Unit
}

private class FakeBannerAdFactory : BannerAdFactory {
    val created = mutableListOf<FakeBannerAd>()

    override fun create(
        context: Context,
        widthDp: Int,
        maxHeightDp: Int,
        onLoadFailed: () -> Unit,
    ): BannerAd = FakeBannerAd(View(context), onLoadFailed).also(created::add)
}

private class FakeBannerAd(
    override val view: View,
    private val onLoadFailed: () -> Unit,
) : BannerAd {
    var loads = 0
    var destroys = 0

    fun failLoad() = onLoadFailed()

    override fun load() {
        loads++
    }

    override fun pause() = Unit

    override fun resume() = Unit

    override fun destroy() {
        destroys++
    }
}

private fun questionState(
    index: Int,
    questionId: String = "general_001",
    selectedAnswerId: String? = null,
    isSubmitted: Boolean = false,
): StudySessionUiState.Ready = StudySessionUiState.Ready(
    question = StudyQuestion(
        releaseId = "release-3",
        id = questionId,
        officialNumber = index + 1,
        scope = "GENERAL",
        stateCode = null,
        category = null,
        correctAnswerId = "A",
        germanQuestionText = "Was ist richtig?",
        localizedQuestionText = "Hangisi doğrudur?",
        explanation = "Das ist die Erklärung.",
        languageCode = "tr",
        questionImage = null,
        answers = listOf(
            StudyAnswer("A", 0, "Antwort A", "Cevap A", null),
            StudyAnswer("B", 1, "Antwort B", "Cevap B", null),
        ),
        explanationStatus = "AI_GENERATED_NEEDS_REVIEW",
        reviewed = false,
        memoryKey = null,
    ),
    currentIndex = index,
    totalCount = 2,
    selectedAnswerId = selectedAnswerId,
    isSubmitted = isSubmitted,
)
