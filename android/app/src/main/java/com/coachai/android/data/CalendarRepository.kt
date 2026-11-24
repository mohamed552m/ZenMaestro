package com.coachai.android.data

import com.coachai.android.data.remote.AuthTokenProvider
import com.coachai.android.data.remote.CalendarRemoteConnection
import com.coachai.android.data.remote.CalendarRemoteDataSource
import com.coachai.android.data.remote.CalendarRemoteItem
import com.coachai.android.data.remote.CalendarRemoteResult
import com.coachai.android.data.remote.withTokenRefreshRetry
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

enum class CalendarFailureReason { AUTHENTICATION_REQUIRED, NETWORK_ERROR, CONFIGURATION_ERROR, INVALID_RESPONSE }
class CalendarRepositoryException(val reason: CalendarFailureReason) : Exception(reason.name)

interface CalendarRepository {
    suspend fun connection(): Result<CalendarRemoteConnection>
    suspend fun beginAuthorization(): Result<String>
    suspend fun completeAuthorization(code: String, state: String): Result<CalendarRemoteConnection>
    suspend fun calendars(): Result<List<CalendarRemoteItem>>
    suspend fun select(calendars: List<CalendarRemoteItem>, key: String): Result<CalendarRemoteConnection>
    suspend fun sync(start: LocalDate, end: LocalDate, key: String): Result<Unit>
    suspend fun disconnect(key: String): Result<CalendarRemoteConnection>
}

class RealCalendarRepository(
    private val authTokenProvider: AuthTokenProvider,
    private val api: CalendarRemoteDataSource,
) : CalendarRepository {
    override suspend fun connection() = withToken(api::connection)
    override suspend fun beginAuthorization() = withToken(api::beginAuthorization)
    override suspend fun completeAuthorization(code: String, state: String) =
        withToken { api.completeAuthorization(it, code, state) }
    override suspend fun calendars() = withToken(api::calendars)
    override suspend fun select(calendars: List<CalendarRemoteItem>, key: String) =
        withToken { api.select(it, calendars, key) }
    override suspend fun sync(start: LocalDate, end: LocalDate, key: String) =
        withToken { api.sync(it, start, end, key) }
    override suspend fun disconnect(key: String) = withToken { api.disconnect(it, key) }

    private suspend fun <T> withToken(block: suspend (String) -> CalendarRemoteResult<T>): Result<T> {
        val result = try {
            authTokenProvider.withTokenRefreshRetry(
                isUnauthorized = { it == CalendarRemoteResult.Unauthorized },
                request = block,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(CalendarFailureReason.NETWORK_ERROR)
        }
        if (result == null) return failure(CalendarFailureReason.AUTHENTICATION_REQUIRED)
        return when (result) {
            is CalendarRemoteResult.Success -> Result.success(result.value)
            CalendarRemoteResult.Unauthorized -> failure(CalendarFailureReason.AUTHENTICATION_REQUIRED)
            CalendarRemoteResult.Forbidden -> failure(CalendarFailureReason.AUTHENTICATION_REQUIRED)
            CalendarRemoteResult.NetworkError -> failure(CalendarFailureReason.NETWORK_ERROR)
            CalendarRemoteResult.ConfigurationError -> failure(CalendarFailureReason.CONFIGURATION_ERROR)
            CalendarRemoteResult.InvalidResponse -> failure(CalendarFailureReason.INVALID_RESPONSE)
        }
    }

    private fun <T> failure(reason: CalendarFailureReason): Result<T> =
        Result.failure(CalendarRepositoryException(reason))
}
