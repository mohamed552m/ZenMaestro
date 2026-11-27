package com.coachai.android.data

import com.coachai.android.data.remote.AuthTokenProvider
import com.coachai.android.data.remote.TaskRemoteDataSource
import com.coachai.android.data.remote.TaskRemoteResult
import com.coachai.android.data.remote.withTokenRefreshRetry
import com.coachai.android.domain.HomeTask
import kotlinx.coroutines.CancellationException

enum class TaskFailureReason {
    AUTHENTICATION_REQUIRED,
    CONFLICT,
    NETWORK_ERROR,
    INVALID_RESPONSE,
    TIMEOUT,
    RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    SERVER_ERROR,
}

internal fun <T> TaskRemoteResult<T>.toTaskResult(): Result<T> = when (this) {
    is TaskRemoteResult.Success -> Result.success(value)
    TaskRemoteResult.Unauthorized,
    TaskRemoteResult.Forbidden -> Result.failure(TaskRepositoryException(TaskFailureReason.AUTHENTICATION_REQUIRED))
    TaskRemoteResult.Conflict -> Result.failure(TaskRepositoryException(TaskFailureReason.CONFLICT))
    TaskRemoteResult.NetworkError -> Result.failure(TaskRepositoryException(TaskFailureReason.NETWORK_ERROR))
    TaskRemoteResult.InvalidResponse -> Result.failure(TaskRepositoryException(TaskFailureReason.INVALID_RESPONSE))
    TaskRemoteResult.Timeout -> Result.failure(TaskRepositoryException(TaskFailureReason.TIMEOUT))
    TaskRemoteResult.RateLimited -> Result.failure(TaskRepositoryException(TaskFailureReason.RATE_LIMITED))
    TaskRemoteResult.ServiceUnavailable -> Result.failure(TaskRepositoryException(TaskFailureReason.SERVICE_UNAVAILABLE))
    TaskRemoteResult.ServerError -> Result.failure(TaskRepositoryException(TaskFailureReason.SERVER_ERROR))
}
class TaskRepositoryException(val reason: TaskFailureReason) : Exception(reason.name)

class TaskRepository(private val tokens: AuthTokenProvider, private val api: TaskRemoteDataSource) {
    suspend fun ensureTodayPlan(key: String) = withToken { api.ensureTodayPlan(it, key) }
    suspend fun ensurePlanDate(date: java.time.LocalDate, key: String) =
        withToken { api.ensurePlanDate(it, date, key) }
    suspend fun loadPlanDay(date: java.time.LocalDate) = withToken { api.loadPlanDay(it, date) }
    suspend fun schedule(planId: Long, revision: Int, workStart: String, key: String) =
        withToken { api.schedule(it, planId, revision, workStart, key) }
    suspend fun move(taskId: Long, revision: Int, newStart: String, key: String) =
        withToken { api.move(it, taskId, revision, newStart, key) }
    suspend fun undoSchedule(operationId: String, key: String) =
        withToken { api.undoSchedule(it, operationId, key) }
    suspend fun draft(input: String) = withToken { api.draft(it, input) }
    suspend fun applyDraft(draft: com.coachai.android.data.remote.PlanningDraftBundle, key: String) =
        withToken { api.applyDraft(it, draft, key) }
    suspend fun create(planId: Long, task: HomeTask, description: String, isBreak: Boolean, key: String) =
        withToken { api.create(it, planId, task, description, isBreak, key) }
    suspend fun command(task: HomeTask, command: String, reason: String?, key: String) =
        withToken { api.command(it, task, command, reason, key) }
    suspend fun delete(task: HomeTask, key: String) = withToken { api.delete(it, task, key) }
    suspend fun transcribe(audio: ByteArray, mimeType: String) = withToken { api.transcribe(it, audio, mimeType) }

    private suspend fun <T> withToken(block: suspend (String) -> TaskRemoteResult<T>): Result<T> {
        val result = try {
            tokens.withTokenRefreshRetry(
                isUnauthorized = { it == TaskRemoteResult.Unauthorized },
                request = block,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(TaskFailureReason.NETWORK_ERROR)
        }
        if (result == null) return failure(TaskFailureReason.AUTHENTICATION_REQUIRED)
        return result.toTaskResult()
    }
    private fun <T> failure(reason: TaskFailureReason): Result<T> = Result.failure(TaskRepositoryException(reason))
}
