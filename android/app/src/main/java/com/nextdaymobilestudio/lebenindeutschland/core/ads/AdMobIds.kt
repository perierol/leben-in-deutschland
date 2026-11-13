package com.nextdaymobilestudio.lebenindeutschland.core.ads

/**
 * Guards against shipping Google's test IDs, in addition to the release build check in
 * `app/build.gradle.kts`.
 */
object AdMobIds {
    /** Publisher ID of Google's published sample app and test ad units. */
    const val GOOGLE_TEST_PUBLISHER_ID = "ca-app-pub-3940256099942544"

    private val AD_UNIT_ID = Regex("""ca-app-pub-\d{16}/\d{10}""")

    fun isGoogleTestId(id: String): Boolean = id.startsWith(GOOGLE_TEST_PUBLISHER_ID)

    /**
     * Returns the ad unit ID to use, or null when ads must stay off: a malformed ID, or a
     * test ID outside a debug build.
     */
    fun usableAdUnitId(adUnitId: String, isDebugBuild: Boolean): String? = when {
        !AD_UNIT_ID.matches(adUnitId) -> null
        isGoogleTestId(adUnitId) && !isDebugBuild -> null
        else -> adUnitId
    }
}
