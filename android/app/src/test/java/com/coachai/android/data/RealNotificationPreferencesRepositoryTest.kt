package com.coachai.android.data

import com.coachai.android.data.remote.AuthTokenProvider
import com.coachai.android.data.remote.NotificationPreferences
import com.coachai.android.data.remote.NotificationPreferencesRemoteDataSource
import com.coachai.android.data.remote.NotificationRemoteResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RealNotificationPreferencesRepositoryTest {
    private val preferences = NotificationPreferences(
        defaultReminderLeadMinutes = 10,
        quietHoursStart = "22:00:00",
        quietHoursEnd = "07:00:00",
        dailySummaryEnabled = true,
        coachCheckinsEnabled = true,
        soundEnabled = false,
        vibrationEnabled = true,
    )

    @Test
    fun missingAuthenticationDoesNotCallApi() = runTest {
        var calls = 0
        val repository = RealNotificationPreferencesRepository(
            AuthTokenProvider { null },
            fakeRemote(onLoad = { calls++; NotificationRemoteResult.Success(preferences) }),
        )

        val result = repository.load()

        assertEquals(NotificationFailureReason.AUTHENTICATION_REQUIRED, result.reason())
        assertEquals(0, calls)
    }

    @Test
    fun updateForwardsChangesAndIdempotencyKey() = runTest {
        var receivedChanges: Map<String, Any?> = emptyMap()
        var receivedKey = ""
        val repository = RealNotificationPreferencesRepository(
            AuthTokenProvider { "token" },
            fakeRemote(onUpdate = { _, changes, key ->
                receivedChanges = changes
                receivedKey = key
                NotificationRemoteResult.Success(preferences)
            }),
        )

        val result = repository.update(mapOf("sound_enabled" to false), "stable-key")

        assertEquals(preferences, result.getOrThrow())
        assertEquals(mapOf("sound_enabled" to false), receivedChanges)
        assertEquals("stable-key", receivedKey)
    }

    @Test
    fun remoteFailuresMapToStableReasons() = runTest {
        val cases = listOf(
            NotificationRemoteResult.Unauthorized to NotificationFailureReason.AUTHENTICATION_REQUIRED,
            NotificationRemoteResult.Forbidden to NotificationFailureReason.AUTHENTICATION_REQUIRED,
            NotificationRemoteResult.NetworkError to NotificationFailureReason.NETWORK_ERROR,
            NotificationRemoteResult.InvalidResponse to NotificationFailureReason.INVALID_RESPONSE,
        )
        cases.forEach { (remote, expected) ->
            val repository = RealNotificationPreferencesRepository(
                AuthTokenProvider { "token" }, fakeRemote(onLoad = { remote }),
            )
            assertEquals(expected, repository.load().reason())
        }
    }

    private fun fakeRemote(
        onLoad: suspend () -> NotificationRemoteResult = { NotificationRemoteResult.Success(preferences) },
        onUpdate: suspend (String, Map<String, Any?>, String) -> NotificationRemoteResult = { _, _, _ ->
            NotificationRemoteResult.Success(preferences)
        },
    ) = object : NotificationPreferencesRemoteDataSource {
        override suspend fun load(idToken: String) = onLoad()
        override suspend fun update(idToken: String, changes: Map<String, Any?>, idempotencyKey: String) =
            onUpdate(idToken, changes, idempotencyKey)
    }

    private fun Result<*>.reason(): NotificationFailureReason? =
        (exceptionOrNull() as? NotificationRepositoryException)?.reason
}
