package com.coachai.android.data

import com.coachai.android.data.remote.AuthTokenProvider
import com.coachai.android.data.remote.FuturePlanningRemoteDataSource
import com.coachai.android.data.remote.FuturePlanningRequest
import com.coachai.android.data.remote.TaskRemoteResult
import com.coachai.android.data.remote.withTokenRefreshRetry
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

class FuturePlanningRepository(
    private val tokens: AuthTokenProvider,
    private val api: FuturePlanningRemoteDataSource,
) {
    suspend fun candidates(start: LocalDate, end: LocalDate) =
        withToken { api.candidates(it, start, end) }
    suspend fun preview(request: FuturePlanningRequest) =
        withToken { api.preview(it, request) }
    suspend fun apply(request: FuturePlanningRequest, key: String) =
        withToken { api.apply(it, request, key) }
    suspend fun undo(operationId: String, key: String) =
        withToken { api.undo(it, operationId, key) }

    private suspend fun <T> withToken(block: suspend (String) -> TaskRemoteResult<T>): Result<T> {
        val result = try {
            tokens.withTokenRefreshRetry(
                isUnauthorized = { it == TaskRemoteResult.Unauthorized },
                request = block,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Result.failure(TaskRepositoryException(TaskFailureReason.NETWORK_ERROR))
        }
        if (result == null) {
            return Result.failure(TaskRepositoryException(TaskFailureReason.AUTHENTICATION_REQUIRED))
        }
        return when (result) {
            is TaskRemoteResult.Success -> Result.success(result.value)
            TaskRemoteResult.Unauthorized -> Result.failure(TaskRepositoryException(TaskFailureReason.AUTHENTICATION_REQUIRED))
            TaskRemoteResult.Forbidden -> Result.failure(TaskRepositoryException(TaskFailureReason.AUTHENTICATION_REQUIRED))
            TaskRemoteResult.Conflict -> Result.failure(TaskRepositoryException(TaskFailureReason.CONFLICT))
            TaskRemoteResult.NetworkError -> Result.failure(TaskRepositoryException(TaskFailureReason.NETWORK_ERROR))
            TaskRemoteResult.InvalidResponse -> Result.failure(TaskRepositoryException(TaskFailureReason.INVALID_RESPONSE))
        }
    }
}
