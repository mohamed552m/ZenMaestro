package com.coachai.android.data

import com.coachai.android.data.remote.AuthTokenProvider
import com.coachai.android.data.remote.CoachRecommendation
import com.coachai.android.data.remote.CoachRemoteDataSource
import com.coachai.android.data.remote.CoachRemoteResult
import com.coachai.android.data.remote.withTokenRefreshRetry
import kotlinx.coroutines.CancellationException

enum class CoachFailureReason {
    AUTHENTICATION_REQUIRED,
    NETWORK_ERROR,
    CONFIGURATION_ERROR,
    INVALID_RESPONSE,
    AI_UNAVAILABLE,
    NO_SCHEDULE,
    TIMEOUT,
}
class CoachRepositoryException(val reason: CoachFailureReason) : Exception(reason.name)

interface CoachRepository {
    suspend fun recommendations(): Result<CoachRecommendation>
    suspend fun chat(message: String): Result<String>
}

class RealCoachRepository(
    private val tokens: AuthTokenProvider,
    private val api: CoachRemoteDataSource,
) : CoachRepository {
    override suspend fun recommendations() = withToken(api::recommendations)
    override suspend fun chat(message: String) = withToken { api.chat(it, message) }

    private suspend fun <T> withToken(block: suspend (String) -> CoachRemoteResult<T>): Result<T> {
        val result = try {
            tokens.withTokenRefreshRetry(
                isUnauthorized = { it == CoachRemoteResult.Unauthorized },
                request = block,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(CoachFailureReason.NETWORK_ERROR)
        }
        if (result == null) return failure(CoachFailureReason.AUTHENTICATION_REQUIRED)
        return when (result) {
            is CoachRemoteResult.Success -> Result.success(result.value)
            CoachRemoteResult.Unauthorized -> failure(CoachFailureReason.AUTHENTICATION_REQUIRED)
            CoachRemoteResult.Forbidden -> failure(CoachFailureReason.AUTHENTICATION_REQUIRED)
            CoachRemoteResult.NetworkError -> failure(CoachFailureReason.NETWORK_ERROR)
            CoachRemoteResult.ConfigurationError -> failure(CoachFailureReason.CONFIGURATION_ERROR)
            CoachRemoteResult.InvalidResponse -> failure(CoachFailureReason.INVALID_RESPONSE)
            CoachRemoteResult.ProviderUnavailable -> failure(CoachFailureReason.AI_UNAVAILABLE)
            CoachRemoteResult.NoSchedule -> failure(CoachFailureReason.NO_SCHEDULE)
            CoachRemoteResult.Timeout -> failure(CoachFailureReason.TIMEOUT)
        }
    }

    private fun <T> failure(reason: CoachFailureReason): Result<T> = Result.failure(CoachRepositoryException(reason))
}
