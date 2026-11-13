package com.nextdaymobilestudio.lebenindeutschland.testing

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.nextdaymobilestudio.lebenindeutschland.LebenInDeutschlandApplication

/**
 * Runs instrumented tests in an application with ads disabled, so tests that start the
 * real MainActivity never contact Google for consent or ads.
 */
class AdsDisabledTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(
        classLoader,
        AdsDisabledTestApplication::class.java.name,
        context,
    )
}

class AdsDisabledTestApplication : LebenInDeutschlandApplication() {
    override val adsEnabled: Boolean = false
}
