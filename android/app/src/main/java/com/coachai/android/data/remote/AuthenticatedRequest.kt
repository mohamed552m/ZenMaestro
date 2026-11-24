package com.coachai.android.data.remote

/**
 * Runs an authenticated request and retries it once with a forced Firebase token
 * refresh only when the first response is an HTTP 401 equivalent.
 *
 * Returning null means there is no signed-in user/token. Exceptions deliberately
 * propagate so repositories can preserve their existing cancellation/network mapping.
 */
suspend fun <T> AuthTokenProvider.withTokenRefreshRetry(
    isUnauthorized: (T) -> Boolean,
    request: suspend (token: String) -> T,
): T? {
    val token = firebaseIdToken()?.takeIf(String::isNotBlank) ?: return null
    val firstResult = request(token)
    if (!isUnauthorized(firstResult)) return firstResult

    val refreshedToken = refreshFirebaseIdToken(token)
        ?.takeIf(String::isNotBlank)
        ?: run {
            invalidateSession(token)
            return firstResult
        }
    val retryResult = request(refreshedToken)
    if (isUnauthorized(retryResult)) invalidateSession(refreshedToken)
    return retryResult
}
