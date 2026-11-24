package com.coachai.android.data.remote

fun interface AuthTokenProvider {
    suspend fun firebaseIdToken(): String?

    /**
     * Returns a fresh token after [rejectedToken] received an HTTP 401.
     *
     * The default keeps lightweight test providers source-compatible. The Firebase
     * implementation overrides this to force a server refresh and coalesce parallel
     * refresh attempts.
     */
    suspend fun refreshFirebaseIdToken(rejectedToken: String): String? = firebaseIdToken()

    /**
     * Clears a session that the backend rejected even after a forced token refresh.
     *
     * The default is intentionally a no-op for unconfigured and test providers.
     */
    suspend fun invalidateSession(rejectedToken: String) = Unit
}

object UnconfiguredAuthTokenProvider : AuthTokenProvider {
    override suspend fun firebaseIdToken(): String? = null
}
