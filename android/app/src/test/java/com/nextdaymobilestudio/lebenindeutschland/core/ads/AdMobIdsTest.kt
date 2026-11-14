package com.nextdaymobilestudio.lebenindeutschland.core.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdMobIdsTest {
    @Test
    fun debugBuildsUseGoogleTestAdUnits() {
        assertEquals(TEST_BANNER, AdMobIds.usableAdUnitId(TEST_BANNER, isDebugBuild = true))
    }

    @Test
    fun releaseBuildsNeverUseGoogleTestAdUnits() {
        assertNull(AdMobIds.usableAdUnitId(TEST_BANNER, isDebugBuild = false))
    }

    @Test
    fun releaseBuildsUseARealAdUnit() {
        assertEquals(REAL_BANNER, AdMobIds.usableAdUnitId(REAL_BANNER, isDebugBuild = false))
    }

    @Test
    fun missingOrMalformedIdsTurnAdsOff() {
        assertNull(AdMobIds.usableAdUnitId("", isDebugBuild = false))
        assertNull(AdMobIds.usableAdUnitId("", isDebugBuild = true))
        assertNull(AdMobIds.usableAdUnitId("ca-app-pub-123/456", isDebugBuild = false))
        assertNull(AdMobIds.usableAdUnitId("ca-app-pub-1234567890123456~1234567890", false))
    }

    private companion object {
        const val TEST_BANNER = "ca-app-pub-3940256099942544/9214589741"
        const val REAL_BANNER = "ca-app-pub-1234567890123456/1234567890"
    }
}
