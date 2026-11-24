package com.coachai.android.data

import com.coachai.android.data.remote.AnalyticsRemoteDataSource
import com.coachai.android.data.remote.AnalyticsRemoteResult
import com.coachai.android.data.remote.AuthTokenProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RealAnalyticsRepositoryTest {
    @Test
    fun missingAuthenticationDoesNotCallAnalyticsApi() = runTest {
        var calls = 0
        val repository = RealAnalyticsRepository(
            AuthTokenProvider { null },
            AnalyticsRemoteDataSource { _, _, _ ->
                calls++
                AnalyticsRemoteResult.Success("{}")
            },
        )

        val result = repository.loadProfile(30)

        assertEquals(AnalyticsFailureReason.AUTHENTICATION_REQUIRED, result.reason())
        assertEquals(0, calls)
    }

    @Test
    fun successfulPayloadAndRequestedWindowAreForwarded() = runTest {
        var requestedWindow = 0
        var requestedLanguage = ""
        val repository = RealAnalyticsRepository(
            AuthTokenProvider { "token" },
            AnalyticsRemoteDataSource { _, window, language ->
                requestedWindow = window
                requestedLanguage = language
                AnalyticsRemoteResult.Success("{\"sample_size\":0}")
            },
        )

        val result = repository.loadProfile(90, "ar")

        assertEquals("{\"sample_size\":0}", result.getOrThrow())
        assertEquals(90, requestedWindow)
        assertEquals("ar", requestedLanguage)
    }

    @Test
    fun remoteFailuresMapToStableUiReasons() = runTest {
        val cases = listOf(
            AnalyticsRemoteResult.Unauthorized to AnalyticsFailureReason.AUTHENTICATION_REQUIRED,
            AnalyticsRemoteResult.Forbidden to AnalyticsFailureReason.AUTHENTICATION_REQUIRED,
            AnalyticsRemoteResult.NetworkError to AnalyticsFailureReason.NETWORK_ERROR,
            AnalyticsRemoteResult.InvalidResponse to AnalyticsFailureReason.INVALID_RESPONSE,
        )
        cases.forEach { (remote, expected) ->
            val repository = RealAnalyticsRepository(
                AuthTokenProvider { "token" },
                AnalyticsRemoteDataSource { _, _, _ -> remote },
            )
            assertEquals(expected, repository.loadProfile(30).reason())
        }
    }

    private fun Result<*>.reason(): AnalyticsFailureReason? =
        (exceptionOrNull() as? AnalyticsRepositoryException)?.reason
}
