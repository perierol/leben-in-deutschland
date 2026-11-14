package com.nextdaymobilestudio.lebenindeutschland.core.ads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentAwareAdsControllerTest {
    private val initializer = FakeMobileAdsInitializer()
    private val reports = mutableListOf<String>()
    private val controller = ConsentAwareAdsController(
        mobileAdsInitializer = initializer,
        reportError = { message, _ -> reports += message },
    )

    @Test
    fun availabilityIsUnknownUntilConsentIsGathered() {
        assertEquals(AdsAvailability.UNKNOWN, controller.availability.value)
        assertEquals(0, initializer.calls)
    }

    @Test
    fun grantedConsentInitializesOnceAndMakesAdsAvailable() = runTest {
        val gateway = FakeConsentGateway(canRequestAdsAfterForm = true)

        controller.gatherConsent(gateway)

        assertEquals(listOf("update", "form"), gateway.calls)
        assertEquals(AdsAvailability.AVAILABLE, controller.availability.value)
        assertEquals(1, initializer.calls)
    }

    @Test
    fun consentFromAnEarlierSessionAllowsAdsBeforeTheForm() = runTest {
        val gateway = FakeConsentGateway(canRequestAdsAfterUpdate = true)
        val formShown = CompletableDeferred<Unit>()
        gateway.onForm = { formShown.await() }

        val gathering = launch { controller.gatherConsent(gateway) }
        runCurrent()

        assertEquals(AdsAvailability.AVAILABLE, controller.availability.value)
        formShown.complete(Unit)
        gathering.join()
        assertEquals(1, initializer.calls)
    }

    @Test
    fun declinedConsentKeepsAdsUnavailableWithoutInitializing() = runTest {
        controller.gatherConsent(FakeConsentGateway(canRequestAdsAfterForm = false))

        assertEquals(AdsAvailability.UNAVAILABLE, controller.availability.value)
        assertEquals(0, initializer.calls)
    }

    @Test
    fun failedUpdateFallsBackToThePreviousSessionsConsent() = runTest {
        val gateway = FakeConsentGateway(
            failUpdate = true,
            canRequestAdsAfterUpdate = true,
        )

        controller.gatherConsent(gateway)

        assertEquals(listOf("update"), gateway.calls)
        assertEquals(AdsAvailability.AVAILABLE, controller.availability.value)
        assertEquals(listOf("Ad consent could not be gathered"), reports)
    }

    @Test
    fun failedUpdateWithoutEarlierConsentLeavesAdsOffAndRetriesNextTime() = runTest {
        val offline = FakeConsentGateway(failUpdate = true)
        controller.gatherConsent(offline)
        assertEquals(AdsAvailability.UNAVAILABLE, controller.availability.value)

        val online = FakeConsentGateway(canRequestAdsAfterForm = true)
        controller.gatherConsent(online)

        assertEquals(listOf("update", "form"), online.calls)
        assertEquals(AdsAvailability.AVAILABLE, controller.availability.value)
    }

    @Test
    fun consentIsGatheredOnlyOncePerProcessAfterSuccess() = runTest {
        controller.gatherConsent(FakeConsentGateway(canRequestAdsAfterForm = true))
        val secondVisit = FakeConsentGateway(canRequestAdsAfterForm = true)

        controller.gatherConsent(secondVisit)

        assertTrue(secondVisit.calls.isEmpty())
        assertEquals(1, initializer.calls)
    }

    @Test
    fun failedInitializationIsReportedAndLeavesAdsUnavailable() = runTest {
        initializer.failure = IllegalStateException("init failed")

        controller.gatherConsent(FakeConsentGateway(canRequestAdsAfterForm = true))

        assertEquals(AdsAvailability.UNAVAILABLE, controller.availability.value)
        assertEquals(listOf("Mobile Ads SDK could not be initialized"), reports)
    }

    @Test
    fun privacyOptionsRequirementFollowsTheConsentSdk() = runTest {
        controller.gatherConsent(FakeConsentGateway(privacyOptionsRequired = true))

        assertTrue(controller.isPrivacyOptionsRequired.value)
    }

    @Test
    fun withdrawingConsentInPrivacyOptionsTurnsAdsOff() = runTest {
        val gateway = FakeConsentGateway(
            canRequestAdsAfterForm = true,
            privacyOptionsRequired = true,
        )
        controller.gatherConsent(gateway)
        assertEquals(AdsAvailability.AVAILABLE, controller.availability.value)

        gateway.onPrivacyOptions = { gateway.canRequestAds = false }
        controller.showPrivacyOptions(gateway)

        assertEquals(AdsAvailability.UNAVAILABLE, controller.availability.value)
    }

    @Test
    fun disabledControllerNeverTouchesConsentOrAds() = runTest {
        val gateway = FakeConsentGateway(canRequestAdsAfterForm = true)

        AdsController.Disabled.gatherConsent(gateway)
        AdsController.Disabled.showPrivacyOptions(gateway)

        assertTrue(gateway.calls.isEmpty())
        assertEquals(AdsAvailability.UNAVAILABLE, AdsController.Disabled.availability.value)
    }
}

private class FakeMobileAdsInitializer : MobileAdsInitializer {
    var calls = 0
    var failure: Exception? = null

    override suspend fun initialize() {
        calls++
        failure?.let { throw it }
    }
}

private class FakeConsentGateway(
    private val failUpdate: Boolean = false,
    private val canRequestAdsAfterUpdate: Boolean = false,
    private val canRequestAdsAfterForm: Boolean = canRequestAdsAfterUpdate,
    private val privacyOptionsRequired: Boolean = false,
) : ConsentGateway {
    val calls = mutableListOf<String>()
    var canRequestAds = false
    var onForm: suspend () -> Unit = {}
    var onPrivacyOptions: () -> Unit = {}

    override suspend fun requestConsentInfoUpdate() {
        calls += "update"
        // UMP keeps the previous session's status, even when the update fails.
        canRequestAds = canRequestAdsAfterUpdate
        if (failUpdate) throw ConsentException("offline")
    }

    override suspend fun loadAndShowConsentFormIfRequired() {
        calls += "form"
        onForm()
        canRequestAds = canRequestAdsAfterForm
    }

    override suspend fun showPrivacyOptionsForm() {
        calls += "privacy-options"
        onPrivacyOptions()
    }

    override fun canRequestAds(): Boolean = canRequestAds

    override fun isPrivacyOptionsRequired(): Boolean = privacyOptionsRequired
}
