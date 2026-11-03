package com.nextdaymobilestudio.lebenindeutschland.core.content.remote

/**
 * Establishes the caller identity that remote content reads require.
 *
 * Firestore rules only serve the content catalogue to an authenticated caller, so
 * every sync has to hold a session before it issues its first read. The catalogue
 * itself is the same for everyone, so an anonymous identity is enough: it carries no
 * personal data and needs no sign-in screen.
 */
fun interface ContentAuthenticator {
    suspend fun ensureAuthenticated()

    companion object {
        /** Performs no authentication. Used by tests and by offline code paths. */
        val None: ContentAuthenticator = ContentAuthenticator { }
    }
}
