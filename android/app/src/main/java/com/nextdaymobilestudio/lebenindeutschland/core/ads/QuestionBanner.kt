package com.nextdaymobilestudio.lebenindeutschland.core.ads

import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

object QuestionBannerTestTags {
    const val SLOT = "question-banner-slot"
}

/** Size and screen rules for the banner on practice question screens. */
object QuestionBannerLayout {
    const val MAX_HEIGHT_DP = 60
    const val HORIZONTAL_PADDING_DP = 20
    private const val MIN_SCREEN_HEIGHT_DP = 560
    private const val MIN_FONT_SCALED_HEIGHT_DP = 420

    /**
     * Whether the screen has room for the banner. Large text makes the top bar, question
     * and action button taller, so the required height grows with the font scale. Phones
     * in landscape fall below the threshold and show no banner.
     */
    fun fitsScreen(windowHeightDp: Int, fontScale: Float): Boolean =
        windowHeightDp >= MIN_SCREEN_HEIGHT_DP &&
            windowHeightDp / fontScale.coerceAtLeast(1f) >= MIN_FONT_SCALED_HEIGHT_DP

    fun adWidthDp(windowWidthDp: Int): Int =
        (windowWidthDp - 2 * HORIZONTAL_PADDING_DP).coerceAtLeast(0)
}

@Stable
class QuestionBanner internal constructor() {
    lateinit var ad: BannerAd
        internal set

    var loadFailed by mutableStateOf(false)
        internal set
}

/**
 * Holds one banner for a whole visit to a practice screen.
 *
 * Call it from the route, outside the question content: the banner is created and
 * loaded once, keeps its ad across Check, Next and the loading step between questions,
 * and relies on AdMob's own refresh instead of new requests. It returns null, and
 * nothing is created, when ads are unavailable, the screen is too small for the text
 * size, or ads only became available after the visit started.
 */
@Composable
fun rememberQuestionBanner(
    adsController: AdsController,
    bannerAdFactory: BannerAdFactory?,
): QuestionBanner? {
    // Decided once per visit: a banner that appeared mid-question would push the
    // answers down under the learner's finger.
    val availableAtEntry = remember(adsController) {
        adsController.availability.value == AdsAvailability.AVAILABLE
    }
    val availability by adsController.availability.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val windowWidthDp = with(density) { windowSize.width.toDp().value.toInt() }
    val windowHeightDp = with(density) { windowSize.height.toDp().value.toInt() }
    val showBanner = availableAtEntry &&
        availability == AdsAvailability.AVAILABLE &&
        QuestionBannerLayout.fitsScreen(windowHeightDp, density.fontScale)
    if (!showBanner || bannerAdFactory == null) return null

    val context = LocalContext.current
    val banner = remember(bannerAdFactory) {
        QuestionBanner().also { banner ->
            banner.ad = bannerAdFactory.create(
                context = context,
                widthDp = QuestionBannerLayout.adWidthDp(windowWidthDp),
                maxHeightDp = QuestionBannerLayout.MAX_HEIGHT_DP,
                onLoadFailed = { banner.loadFailed = true },
            )
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(banner, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> banner.ad.resume()
                Lifecycle.Event.ON_PAUSE -> banner.ad.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(banner) {
        onDispose { banner.ad.destroy() }
    }
    LaunchedEffect(banner) {
        banner.ad.load()
    }
    return banner
}

/**
 * The fixed banner band under a screen's top bar, separated from the content below by a
 * divider. Screen readers reach it after the screen's content.
 *
 * [collapseKey] changes when the content changes, for example the question index. A
 * banner that failed to load keeps its space until then, so the content never shifts
 * while the learner may be tapping.
 */
@Composable
fun QuestionBannerSlot(
    banner: QuestionBanner,
    collapseKey: Any?,
    modifier: Modifier = Modifier,
) {
    var collapsed by remember(banner) { mutableStateOf(false) }
    LaunchedEffect(banner, collapseKey) {
        if (banner.loadFailed) collapsed = true
    }
    if (collapsed) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                isTraversalGroup = true
                traversalIndex = 1f
            }
            .testTag(QuestionBannerTestTags.SLOT),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = QuestionBannerLayout.HORIZONTAL_PADDING_DP.dp,
                    vertical = 8.dp,
                )
                .height(QuestionBannerLayout.MAX_HEIGHT_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = {
                    // The same view returns after the loading step between questions.
                    banner.ad.view.also { view -> (view.parent as? ViewGroup)?.removeView(view) }
                },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
