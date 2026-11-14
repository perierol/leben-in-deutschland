package com.nextdaymobilestudio.lebenindeutschland.core.ads

import android.app.Activity
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** The consent operations [AdsController] needs, so it can be tested without an Activity. */
interface ConsentGateway {
    suspend fun requestConsentInfoUpdate()

    suspend fun loadAndShowConsentFormIfRequired()

    suspend fun showPrivacyOptionsForm()

    fun canRequestAds(): Boolean

    fun isPrivacyOptionsRequired(): Boolean
}

class ConsentException(message: String) : Exception(message)

/**
 * Google User Messaging Platform (UMP) consent, bound to the Activity that shows its forms.
 *
 * UMP is touched only when a method is called, so creating this is free when ads are
 * disabled.
 */
class UmpConsentGateway(
    private val activity: Activity,
) : ConsentGateway {
    private val consentInformation: ConsentInformation by lazy {
        UserMessagingPlatform.getConsentInformation(activity)
    }

    override suspend fun requestConsentInfoUpdate() = suspendCancellableCoroutine { continuation ->
        consentInformation.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            { if (continuation.isActive) continuation.resume(Unit) },
            { formError -> continuation.failWith(formError) },
        )
    }

    override suspend fun loadAndShowConsentFormIfRequired() =
        suspendCancellableCoroutine { continuation ->
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                if (formError == null) {
                    if (continuation.isActive) continuation.resume(Unit)
                } else {
                    continuation.failWith(formError)
                }
            }
        }

    override suspend fun showPrivacyOptionsForm() = suspendCancellableCoroutine { continuation ->
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError == null) {
                if (continuation.isActive) continuation.resume(Unit)
            } else {
                continuation.failWith(formError)
            }
        }
    }

    override fun canRequestAds(): Boolean = consentInformation.canRequestAds()

    override fun isPrivacyOptionsRequired(): Boolean =
        consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    private fun kotlinx.coroutines.CancellableContinuation<Unit>.failWith(formError: FormError) {
        if (isActive) {
            resumeWithException(ConsentException("${formError.errorCode}: ${formError.message}"))
        }
    }
}
