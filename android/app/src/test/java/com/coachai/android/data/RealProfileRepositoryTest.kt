package com.coachai.android.data

import com.coachai.android.data.remote.AuthTokenProvider
import com.coachai.android.data.remote.ProfileData
import com.coachai.android.data.remote.ProfileRemoteDataSource
import com.coachai.android.data.remote.ProfileRemoteResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealProfileRepositoryTest {
    private val profile = ProfileData(
        userId = "u1",
        displayName = "Mona Ali",
        email = "mona@example.test",
        timezone = "Africa/Cairo",
        createdAt = "2026-01-01T00:00:00Z",
    )

    @Test
    fun missingAuthenticationFailsWithoutCallingRemote() = runTest {
        val remote = FakeProfileRemote(ProfileRemoteResult.Success(profile))
        val result = repository(null, remote).load()

        assertEquals(ProfileFailureReason.AUTHENTICATION_REQUIRED, result.failureReason())
        assertEquals(0, remote.calls)
    }

    @Test
    fun loadReturnsOnlyConfirmedRemoteProfile() = runTest {
        val remote = FakeProfileRemote(ProfileRemoteResult.Success(profile))
        val result = repository("token", remote).load()

        assertEquals(profile, result.getOrThrow())
        assertEquals(1, remote.calls)
    }

    @Test
    fun remoteFailuresMapToStableReasons() = runTest {
        val cases = listOf(
            ProfileRemoteResult.Unauthorized to ProfileFailureReason.AUTHENTICATION_REQUIRED,
            ProfileRemoteResult.Forbidden to ProfileFailureReason.AUTHENTICATION_REQUIRED,
            ProfileRemoteResult.NetworkError to ProfileFailureReason.NETWORK_ERROR,
            ProfileRemoteResult.InvalidResponse to ProfileFailureReason.INVALID_RESPONSE,
        )

        cases.forEach { (remoteResult, expected) ->
            val result = repository("token", FakeProfileRemote(remoteResult)).load()
            assertEquals(expected, result.failureReason())
        }
    }

    @Test
    fun updatesForwardIdempotencyAndUseServerConfirmedState() = runTest {
        val updated = profile.copy(displayName = "Mona A.", timezone = "Asia/Riyadh")
        val remote = FakeProfileRemote(ProfileRemoteResult.Success(updated))
        val repository = repository("token", remote)

        val name = repository.updateDisplayName("Mona A.", "name-key").getOrThrow()
        val timezone = repository.updateTimezone("Asia/Riyadh", "timezone-key").getOrThrow()

        assertEquals(updated, name)
        assertEquals("Asia/Riyadh", timezone)
        assertEquals("name-key", remote.lastNameKey)
        assertEquals("timezone-key", remote.lastTimezoneKey)
    }

    @Test
    fun tokenProviderFailureIsReportedAsNetworkWithoutLeakingDetails() = runTest {
        val remote = FakeProfileRemote(ProfileRemoteResult.Success(profile))
        val repository = RealProfileRepository(
            authTokenProvider = AuthTokenProvider { error("secret provider detail") },
            profileApi = remote,
        )

        val result = repository.load()

        assertEquals(ProfileFailureReason.NETWORK_ERROR, result.failureReason())
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("NETWORK_ERROR"))
        assertEquals(0, remote.calls)
    }

    private fun repository(
        token: String?,
        remote: ProfileRemoteDataSource,
    ) = RealProfileRepository(
        authTokenProvider = AuthTokenProvider { token },
        profileApi = remote,
    )

    private fun Result<*>.failureReason(): ProfileFailureReason? =
        (exceptionOrNull() as? ProfileRepositoryException)?.reason

    private class FakeProfileRemote(
        private val result: ProfileRemoteResult<ProfileData>,
    ) : ProfileRemoteDataSource {
        var calls = 0
        var lastNameKey: String? = null
        var lastTimezoneKey: String? = null

        override suspend fun getProfile(idToken: String): ProfileRemoteResult<ProfileData> {
            calls++
            return result
        }

        override suspend fun updateDisplayName(
            idToken: String,
            displayName: String,
            idempotencyKey: String,
        ): ProfileRemoteResult<ProfileData> {
            calls++
            lastNameKey = idempotencyKey
            return result
        }

        override suspend fun updateTimezone(
            idToken: String,
            timezone: String,
            idempotencyKey: String,
        ): ProfileRemoteResult<String> {
            calls++
            lastTimezoneKey = idempotencyKey
            return when (result) {
                is ProfileRemoteResult.Success -> ProfileRemoteResult.Success(
                    result.value.timezone,
                )
                ProfileRemoteResult.Unauthorized -> ProfileRemoteResult.Unauthorized
                ProfileRemoteResult.Forbidden -> ProfileRemoteResult.Forbidden
                ProfileRemoteResult.NetworkError -> ProfileRemoteResult.NetworkError
                ProfileRemoteResult.InvalidResponse -> ProfileRemoteResult.InvalidResponse
            }
        }
    }
}
