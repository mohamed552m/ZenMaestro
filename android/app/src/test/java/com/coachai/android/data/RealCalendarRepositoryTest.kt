package com.coachai.android.data

import com.coachai.android.data.remote.AuthTokenProvider
import com.coachai.android.data.remote.CalendarRemoteConnection
import com.coachai.android.data.remote.CalendarRemoteDataSource
import com.coachai.android.data.remote.CalendarRemoteItem
import com.coachai.android.data.remote.CalendarRemoteResult
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RealCalendarRepositoryTest {
    @Test
    fun missingAuthenticationDoesNotCallCalendarApi() = runTest {
        var calls = 0
        val repository = RealCalendarRepository(AuthTokenProvider { null }, fakeRemote { calls++ })

        val result = repository.connection()

        assertEquals(CalendarFailureReason.AUTHENTICATION_REQUIRED, result.reason())
        assertEquals(0, calls)
    }

    @Test
    fun connectionAndFailuresAreMapped() = runTest {
        val expected = CalendarRemoteConnection(false, emptyList())
        val success = RealCalendarRepository(
            AuthTokenProvider { "token" },
            fakeRemote(result = CalendarRemoteResult.Success(expected)),
        )
        assertEquals(expected, success.connection().getOrThrow())

        val cases = listOf(
            CalendarRemoteResult.Unauthorized to CalendarFailureReason.AUTHENTICATION_REQUIRED,
            CalendarRemoteResult.Forbidden to CalendarFailureReason.AUTHENTICATION_REQUIRED,
            CalendarRemoteResult.NetworkError to CalendarFailureReason.NETWORK_ERROR,
            CalendarRemoteResult.ConfigurationError to CalendarFailureReason.CONFIGURATION_ERROR,
            CalendarRemoteResult.InvalidResponse to CalendarFailureReason.INVALID_RESPONSE,
        )
        cases.forEach { (remote, reason) ->
            val repository = RealCalendarRepository(AuthTokenProvider { "token" }, fakeRemote(result = remote))
            assertEquals(reason, repository.connection().reason())
        }
    }

    private fun fakeRemote(
        result: CalendarRemoteResult<CalendarRemoteConnection> = CalendarRemoteResult.Success(CalendarRemoteConnection(false, emptyList())),
        onCall: () -> Unit = {},
    ) = object : CalendarRemoteDataSource {
        override suspend fun connection(token: String): CalendarRemoteResult<CalendarRemoteConnection> { onCall(); return result }
        override suspend fun beginAuthorization(token: String) = CalendarRemoteResult.Success("https://example.test")
        override suspend fun completeAuthorization(token: String, code: String, state: String) = result
        override suspend fun calendars(token: String) = CalendarRemoteResult.Success(emptyList<CalendarRemoteItem>())
        override suspend fun select(token: String, calendars: List<CalendarRemoteItem>, key: String) = result
        override suspend fun sync(token: String, start: LocalDate, end: LocalDate, key: String) = CalendarRemoteResult.Success(Unit)
        override suspend fun disconnect(token: String, key: String) = result
    }

    private fun Result<*>.reason() = (exceptionOrNull() as? CalendarRepositoryException)?.reason
}
