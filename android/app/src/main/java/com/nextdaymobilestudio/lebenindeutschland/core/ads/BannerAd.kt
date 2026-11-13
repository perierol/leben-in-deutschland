package com.nextdaymobilestudio.lebenindeutschland.core.ads

import android.content.Context
import android.view.View
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

/** One banner view and its lifecycle. Tests substitute a fake that makes no requests. */
interface BannerAd {
    val view: View

    fun load()

    fun pause()

    fun resume()

    fun destroy()
}

fun interface BannerAdFactory {
    fun create(
        context: Context,
        widthDp: Int,
        maxHeightDp: Int,
        onLoadFailed: () -> Unit,
    ): BannerAd
}

/**
 * Creates AdMob banners for one ad unit.
 *
 * An inline adaptive size with a maximum height keeps the banner small and lets the
 * screen reserve a fixed slot, so the question content never moves when an ad arrives.
 */
class AdMobBannerAdFactory(
    private val adUnitId: String,
) : BannerAdFactory {
    override fun create(
        context: Context,
        widthDp: Int,
        maxHeightDp: Int,
        onLoadFailed: () -> Unit,
    ): BannerAd {
        val adView = AdView(context).apply {
            setAdSize(AdSize.getInlineAdaptiveBannerAdSize(widthDp, maxHeightDp))
            adUnitId = this@AdMobBannerAdFactory.adUnitId
            adListener = object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) = onLoadFailed()
            }
        }
        return AdMobBannerAd(adView)
    }
}

private class AdMobBannerAd(
    private val adView: AdView,
) : BannerAd {
    override val view: View = adView

    override fun load() = adView.loadAd(AdRequest.Builder().build())

    override fun pause() = adView.pause()

    override fun resume() = adView.resume()

    override fun destroy() = adView.destroy()
}
