package com.coachai.android.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthenticatedRequestTest {
    @Test
    fun successfulRequestDoesNotRefresh() = runBlocking {
        val provider = RecordingTokenProvider(initialToken = "token-1", refreshedToken = "token-2")

        val result = provider.withTokenRefreshRetry(
            isUnauthorized = { it == "unauthorized" },
            request = { token -> "success:$token" },
        )

        assertEquals("success:token-1", result)
        assertEquals(0, provider.refreshCalls)
        assertEquals(0, provider.invalidateCalls)
        assertEquals(emptyList<String>(), provider.invalidatedTokens)
    }

    @Test
    fun unauthorizedRequestRefreshesAndRetriesExactlyOnce() = runBlocking {
        val provider = RecordingTokenProvider(initialToken = "token-1", refreshedToken = "token-2")
        val requestTokens = mutableListOf<String>()

        val result = provider.withTokenRefreshRetry(
            isUnauthorized = { it == "unauthorized" },
            request = { token ->
                requestTokens += token
                if (token == "token-1") "unauthorized" else "success"
            },
        )

        assertEquals("success", result)
        assertEquals(listOf("token-1", "token-2"), requestTokens)
        assertEquals(1, provider.refreshCalls)
        assertEquals(0, provider.invalidateCalls)
        assertEquals(emptyList<String>(), provider.invalidatedTokens)
    }

    @Test
    fun missingTokenDoesNotCallRequest() = runBlocking {
        var requestCalls = 0
        val provider = RecordingTokenProvider(initialToken = null, refreshedToken = "token-2")

        val result = provider.withTokenRefreshRetry(
            isUnauthorized = { false },
            request = {
                requestCalls += 1
                "success"
            },
        )

        assertNull(result)
        assertEquals(0, requestCalls)
        assertEquals(0, provider.refreshCalls)
        assertEquals(0, provider.invalidateCalls)
    }

    @Test
    fun unavailableRefreshReturnsOriginalUnauthorizedResult() = runBlocking {
        var requestCalls = 0
        val provider = RecordingTokenProvider(initialToken = "token-1", refreshedToken = null)

        val result = provider.withTokenRefreshRetry(
            isUnauthorized = { it == "unauthorized" },
            request = {
                requestCalls += 1
                "unauthorized"
            },
        )

        assertEquals("unauthorized", result)
        assertEquals(1, requestCalls)
        assertEquals(1, provider.refreshCalls)
        assertEquals(1, provider.invalidateCalls)
        assertEquals(listOf("token-1"), provider.invalidatedTokens)
    }

    @Test
    fun secondUnauthorizedResponseInvalidatesSession() = runBlocking {
        val provider = RecordingTokenProvider(initialToken = "token-1", refreshedToken = "token-2")
        val requestTokens = mutableListOf<String>()

        val result = provider.withTokenRefreshRetry(
            isUnauthorized = { it == "unauthorized" },
            request = { token ->
                requestTokens += token
                "unauthorized"
            },
        )

        assertEquals("unauthorized", result)
        assertEquals(listOf("token-1", "token-2"), requestTokens)
        assertEquals(1, provider.refreshCalls)
        assertEquals(1, provider.invalidateCalls)
        assertEquals(listOf("token-2"), provider.invalidatedTokens)
    }

    private class RecordingTokenProvider(
        private val initialToken: String?,
        private val refreshedToken: String?,
    ) : AuthTokenProvider {
        var refreshCalls: Int = 0
            private set
        var invalidateCalls: Int = 0
            private set
        val invalidatedTokens = mutableListOf<String>()

        override suspend fun firebaseIdToken(): String? = initialToken

        override suspend fun refreshFirebaseIdToken(rejectedToken: String): String? {
            refreshCalls += 1
            return refreshedToken
        }

        override suspend fun invalidateSession(rejectedToken: String) {
            invalidateCalls += 1
            invalidatedTokens += rejectedToken
        }
    }
}
