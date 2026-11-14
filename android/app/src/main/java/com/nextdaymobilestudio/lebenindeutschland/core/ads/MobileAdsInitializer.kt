package com.nextdaymobilestudio.lebenindeutschland.core.ads

import android.content.Context
import com.google.android.gms.ads.MobileAds
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

fun interface MobileAdsInitializer {
    suspend fun initialize()
}

/** Initializes the Google Mobile Ads SDK off the main thread, as Google recommends. */
class GoogleMobileAdsInitializer(
    private val context: Context,
) : MobileAdsInitializer {
    override suspend fun initialize() = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            // The listener runs once initialization completes, or after Google's timeout.
            MobileAds.initialize(context) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}
