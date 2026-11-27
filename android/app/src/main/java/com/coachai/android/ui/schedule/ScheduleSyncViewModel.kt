package com.coachai.android.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.coachai.android.data.TaskFailureReason
import com.coachai.android.data.TaskRepository
import com.coachai.android.data.TaskRepositoryException
import com.coachai.android.domain.HomeTask
import java.util.UUID
import kotlinx.coroutines.launch

class ScheduleSyncViewModel(private val repository: TaskRepository) : ViewModel() {
    fun ensureTodayPlan(done: (Result<com.coachai.android.data.remote.TodayPlanTarget>) -> Unit) =
        viewModelScope.launch { done(repository.ensureTodayPlan(UUID.randomUUID().toString())) }
    fun ensurePlanDate(date: java.time.LocalDate, done: (Result<com.coachai.android.data.remote.TodayPlanTarget>) -> Unit) =
        viewModelScope.launch { done(repository.ensurePlanDate(date, UUID.randomUUID().toString())) }
    fun loadPlanDay(date: java.time.LocalDate, done: (Result<com.coachai.android.data.remote.PlanDayResult>) -> Unit) =
        viewModelScope.launch { done(repository.loadPlanDay(date)) }
    fun schedule(planId: Long, revision: Int, workStart: String, done: (Result<com.coachai.android.data.remote.ScheduleApplyResult>) -> Unit) =
        viewModelScope.launch { done(repository.schedule(planId, revision, workStart, UUID.randomUUID().toString())) }
    fun move(taskId: Long, revision: Int, newStart: String, done: (Result<com.coachai.android.data.remote.ScheduleMoveResult>) -> Unit) =
        viewModelScope.launch { done(repository.move(taskId, revision, newStart, UUID.randomUUID().toString())) }
    fun undoSchedule(operationId: String, done: (Result<Int>) -> Unit) =
        viewModelScope.launch { done(repository.undoSchedule(operationId, UUID.randomUUID().toString())) }
    fun draft(input: String, done: (Result<com.coachai.android.data.remote.PlanningDraftBundle>) -> Unit) =
        viewModelScope.launch { done(repository.draft(input)) }
    fun applyDraft(draft: com.coachai.android.data.remote.PlanningDraftBundle, done: (Result<com.coachai.android.data.remote.PlanningApplyResult>) -> Unit) =
        viewModelScope.launch { done(repository.applyDraft(draft, UUID.randomUUID().toString())) }
    fun create(planId: Long, task: HomeTask, description: String, isBreak: Boolean, done: (Result<HomeTask>) -> Unit) =
        viewModelScope.launch { done(repository.create(planId, task, description, isBreak, UUID.randomUUID().toString())) }

    fun command(task: HomeTask, command: String, reason: String? = null, done: (Result<HomeTask>) -> Unit) =
        viewModelScope.launch { done(repository.command(task, command, reason, UUID.randomUUID().toString())) }

    fun delete(task: HomeTask, done: (Result<Unit>) -> Unit) =
        viewModelScope.launch { done(repository.delete(task, UUID.randomUUID().toString())) }

    fun transcribe(audio: ByteArray, mimeType: String, done: (Result<String>) -> Unit) =
        viewModelScope.launch { done(repository.transcribe(audio, mimeType)) }

    companion object {
        fun message(error: Throwable): String = when ((error as? TaskRepositoryException)?.reason) {
            TaskFailureReason.AUTHENTICATION_REQUIRED -> "Your session expired. Log in again before saving this change."
            TaskFailureReason.CONFLICT -> "This task changed elsewhere. Refresh the plan and try again."
            TaskFailureReason.NETWORK_ERROR -> "Can't reach the server. Check your connection and try again."
            TaskFailureReason.TIMEOUT -> "The server is taking too long. Refresh first — your change may already be saved."
            TaskFailureReason.RATE_LIMITED -> "Too many requests. Wait a minute and try again."
            TaskFailureReason.SERVICE_UNAVAILABLE -> "The service is temporarily unavailable. Try again shortly."
            TaskFailureReason.SERVER_ERROR -> "Something went wrong on the server. Try again."
            TaskFailureReason.INVALID_RESPONSE, null -> "This task couldn't be saved. Check the details and try again."
        }
    }

    class Factory(private val repository: TaskRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ScheduleSyncViewModel(repository) as T
    }
}
