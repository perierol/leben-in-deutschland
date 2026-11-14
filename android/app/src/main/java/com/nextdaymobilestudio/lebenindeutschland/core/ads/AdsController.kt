package com.nextdaymobilestudio.lebenindeutschland.core.ads

import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AdsAvailability {
    /** Consent has not been checked in this process yet. */
    UNKNOWN,

    /** Consent allows ad requests and the Mobile Ads SDK is initialized. */
    AVAILABLE,

    /** Ads must not be requested: no consent, a failed check, or ads disabled. */
    UNAVAILABLE,
}

/**
 * Decides whether the app may request ads.
 *
 * Nothing in startup, studying or content sync waits for it: screens that show ads
 * read [availability] and show nothing unless it is [AdsAvailability.AVAILABLE].
 */
interface AdsController {
    val availability: StateFlow<AdsAvailability>
    val isPrivacyOptionsRequired: StateFlow<Boolean>

    /** Refreshes consent and shows Google's consent form if it is required. */
    suspend fun gatherConsent(consentGateway: ConsentGateway)

    /** Shows Google's privacy options form, so the learner can change their choice. */
    suspend fun showPrivacyOptions(consentGateway: ConsentGateway)

    companion object {
        val Disabled: AdsController = DisabledAdsController
    }
}

private object DisabledAdsController : AdsController {
    override val availability: StateFlow<AdsAvailability> =
        MutableStateFlow(AdsAvailability.UNAVAILABLE)
    override val isPrivacyOptionsRequired: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun gatherConsent(consentGateway: ConsentGateway) = Unit

    override suspend fun showPrivacyOptions(consentGateway: ConsentGateway) = Unit
}

/** Follows the UMP flow: update consent at launch, show a required form, then initialize. */
class ConsentAwareAdsController(
    private val mobileAdsInitializer: MobileAdsInitializer,
    private val reportError: (String, Throwable) -> Unit = { _, _ -> },
) : AdsController {
    private val _availability = MutableStateFlow(AdsAvailability.UNKNOWN)
    override val availability: StateFlow<AdsAvailability> = _availability.asStateFlow()

    private val _isPrivacyOptionsRequired = MutableStateFlow(false)
    override val isPrivacyOptionsRequired: StateFlow<Boolean> =
        _isPrivacyOptionsRequired.asStateFlow()

    private val lock = Mutex()
    private var consentGathered = false
    private var mobileAdsInitialized = false

    override suspend fun gatherConsent(consentGateway: ConsentGateway) = lock.withLock {
        // Google asks for a consent update at every launch; once per process is enough.
        // A failed attempt is retried at the next opportunity, never in a loop.
        if (consentGathered) return@withLock
        try {
            consentGateway.requestConsentInfoUpdate()
            // Consent from an earlier session can already allow ads before any form.
            refreshFromConsent(consentGateway)
            consentGateway.loadAndShowConsentFormIfRequired()
            consentGathered = true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // UMP falls back to the previous session's consent status, checked below.
            reportError("Ad consent could not be gathered", exception)
        }
        refreshFromConsent(consentGateway)
    }

    override suspend fun showPrivacyOptions(consentGateway: ConsentGateway) = lock.withLock {
        try {
            consentGateway.showPrivacyOptionsForm()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            reportError("Privacy options form could not be shown", exception)
        }
        refreshFromConsent(consentGateway)
    }

    private suspend fun refreshFromConsent(consentGateway: ConsentGateway) {
        _isPrivacyOptionsRequired.value = consentGateway.isPrivacyOptionsRequired()
        if (!consentGateway.canRequestAds()) {
            _availability.value = AdsAvailability.UNAVAILABLE
            return
        }
        if (!mobileAdsInitialized) {
            try {
                mobileAdsInitializer.initialize()
                mobileAdsInitialized = true
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                reportError("Mobile Ads SDK could not be initialized", exception)
                _availability.value = AdsAvailability.UNAVAILABLE
                return
            }
        }
        _availability.value = AdsAvailability.AVAILABLE
    }
}
