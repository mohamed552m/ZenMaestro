package com.coachai.android.ui.future

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.coachai.android.data.FuturePlanningRepository
import com.coachai.android.data.TaskFailureReason
import com.coachai.android.data.TaskRepositoryException
import com.coachai.android.data.remote.FutureCandidate
import com.coachai.android.data.remote.FuturePlanningRequest
import com.coachai.android.data.remote.FuturePreview
import com.coachai.android.data.remote.FutureTaskInput
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class FuturePlanningUiState(
    val specificDay: Boolean = false,
    val start: LocalDate = LocalDate.now(),
    val end: LocalDate = LocalDate.now().plusDays(6),
    val workStart: String = "09:00",
    val candidates: List<FutureCandidate> = emptyList(),
    val selected: Map<Long, FutureTaskInput> = emptyMap(),
    val drafts: List<FutureTaskInput> = emptyList(),
    val preview: FuturePreview? = null,
    val operationId: String? = null,
    val loading: Boolean = false,
    val applying: Boolean = false,
    val error: String? = null,
    val success: String? = null,
)

class FuturePlanningViewModel(
    private val repository: FuturePlanningRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FuturePlanningUiState())
    val state: StateFlow<FuturePlanningUiState> = mutableState.asStateFlow()
    private var candidatesJob: Job? = null

    init { loadCandidates() }

    fun setSpecificDay(value: Boolean) {
        mutableState.update {
            it.copy(specificDay = value, end = if (value) it.start else maxOf(it.end, it.start), preview = null)
        }
        loadCandidates()
    }

    fun setStart(value: LocalDate) {
        val today = LocalDate.now()
        val start = maxOf(value, today)
        mutableState.update {
            val end = if (it.specificDay) start else maxOf(start, it.end).coerceAtMost(start.plusWeeks(12))
            it.copy(start = start, end = end, selected = emptyMap(), preview = null, operationId = null)
        }
        loadCandidates()
    }

    fun setEnd(value: LocalDate) {
        mutableState.update {
            val end = value.coerceAtLeast(it.start).coerceAtMost(it.start.plusWeeks(12))
            it.copy(end = if (it.specificDay) it.start else end, selected = emptyMap(), preview = null, operationId = null)
        }
        loadCandidates()
    }

    fun setWorkStart(value: String) {
        if (value.matches(Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$"))) {
            mutableState.update { it.copy(workStart = value, preview = null) }
        }
    }

    fun toggle(candidate: FutureCandidate) {
        mutableState.update { current ->
            val next = current.selected.toMutableMap()
            if (candidate.taskId in next) next.remove(candidate.taskId)
            else next[candidate.taskId] = FutureTaskInput(
                taskId = candidate.taskId,
                title = candidate.title,
                description = candidate.description,
                remainingMinutes = maxOf(1, candidate.remainingMinutes),
                priority = candidate.priority,
                earliestStart = candidate.earliestStart
                    ?.let { runCatching { java.time.LocalDateTime.parse(it.replace(' ', 'T')) }.getOrNull() }
                    ?.coerceAtLeast(java.time.LocalDateTime.of(current.start, java.time.LocalTime.parse(current.workStart)))
                    ?.takeIf { it.toLocalDate() <= current.end }
                    ?.toString()
                    ?: "${current.start}T${current.workStart}:00",
                deadline = candidate.deadline
                    ?.let { runCatching { java.time.LocalDateTime.parse(it.replace(' ', 'T')) }.getOrNull() }
                    ?.toLocalDate()
                    ?.coerceIn(current.start, current.end)
                    ?.let { "${it}T20:00:00" }
                    ?: "${current.end}T20:00:00",
            )
            current.copy(selected = next, preview = null, operationId = null)
        }
    }

    fun updateSelected(taskId: Long, minutes: Int? = null, priority: Int? = null, deadline: LocalDate? = null) {
        mutableState.update { current ->
            val item = current.selected[taskId] ?: return@update current
            val updated = item.copy(
                remainingMinutes = minutes?.coerceIn(1, 10080) ?: item.remainingMinutes,
                priority = priority?.coerceIn(1, 5) ?: item.priority,
                deadline = deadline?.let { "${it}T20:00:00" } ?: item.deadline,
            )
            current.copy(selected = current.selected + (taskId to updated), preview = null, operationId = null)
        }
    }

    fun addDraft(title: String, minutes: Int, priority: Int, deadline: LocalDate) {
        val clean = title.trim()
        if (clean.isBlank() || minutes !in 1..10080 || deadline < mutableState.value.start) {
            mutableState.update { it.copy(error = "Add a title, a valid duration, and a deadline inside the planning period.") }
            return
        }
        val state = mutableState.value
        val boundedDeadline = deadline.coerceAtMost(state.end)
        mutableState.update {
            it.copy(
                drafts = it.drafts + FutureTaskInput(
                    temporaryId = "draft:${UUID.randomUUID()}",
                    title = clean,
                    remainingMinutes = minutes,
                    priority = priority.coerceIn(1, 5),
                    earliestStart = "${state.start}T${state.workStart}:00",
                    deadline = "${boundedDeadline}T20:00:00",
                ),
                preview = null,
                operationId = null,
                error = null,
            )
        }
    }

    fun removeDraft(temporaryId: String) {
        mutableState.update { current ->
            current.copy(drafts = current.drafts.filterNot { it.temporaryId == temporaryId }, preview = null)
        }
    }

    fun preview() {
        val request = buildRequest() ?: return
        mutableState.update { it.copy(loading = true, error = null, success = null) }
        viewModelScope.launch {
            repository.preview(request)
                .onSuccess { preview -> mutableState.update { it.copy(loading = false, preview = preview) } }
                .onFailure { failure -> mutableState.update { it.copy(loading = false, error = message(failure)) } }
        }
    }

    fun apply(onApplied: () -> Unit) {
        if (mutableState.value.applying || mutableState.value.operationId != null) return
        val request = buildRequest() ?: return
        if (mutableState.value.preview == null) {
            mutableState.update { it.copy(error = "Preview the distribution before saving it.") }
            return
        }
        mutableState.update { it.copy(applying = true, error = null) }
        viewModelScope.launch {
            repository.apply(request, "future-${UUID.randomUUID()}")
                .onSuccess { result ->
                    mutableState.update {
                        it.copy(
                            applying = false,
                            operationId = result.operationId,
                            success = "Your future plan is saved across the selected days.",
                        )
                    }
                    onApplied()
                }
                .onFailure { failure -> mutableState.update { it.copy(applying = false, error = message(failure)) } }
        }
    }

    fun undo(onUndone: () -> Unit) {
        if (mutableState.value.applying) return
        val operationId = mutableState.value.operationId ?: return
        mutableState.update { it.copy(applying = true, error = null) }
        viewModelScope.launch {
            repository.undo(operationId, "future-undo-${UUID.randomUUID()}")
                .onSuccess {
                    mutableState.update { it.copy(applying = false, operationId = null, success = "The future plan was restored.") }
                    onUndone()
                }
                .onFailure { failure -> mutableState.update { it.copy(applying = false, error = message(failure)) } }
        }
    }

    fun clearFeedback() = mutableState.update { it.copy(error = null, success = null) }

    private fun loadCandidates() {
        val snapshot = mutableState.value
        mutableState.update { it.copy(loading = true, error = null) }
        candidatesJob?.cancel()
        candidatesJob = viewModelScope.launch {
            repository.candidates(snapshot.start, snapshot.end)
                .onSuccess { items -> mutableState.update { it.copy(loading = false, candidates = items) } }
                .onFailure { failure -> mutableState.update { it.copy(loading = false, error = message(failure)) } }
        }
    }

    private fun buildRequest(): FuturePlanningRequest? {
        val current = mutableState.value
        val tasks = current.selected.values.toList() + current.drafts
        if (tasks.isEmpty()) {
            mutableState.update { it.copy(error = "Choose at least one existing task or add a new one.") }
            return null
        }
        return FuturePlanningRequest(
            specificDay = current.specificDay,
            planningStart = current.start,
            planningEnd = current.end,
            workDayStart = current.workStart,
            tasks = tasks,
        )
    }

    private fun message(error: Throwable): String = when ((error as? TaskRepositoryException)?.reason) {
        TaskFailureReason.AUTHENTICATION_REQUIRED -> "Sign in again to continue."
        TaskFailureReason.CONFLICT -> "The schedule changed. Refresh the plan and preview it again."
        TaskFailureReason.NETWORK_ERROR -> "You're offline. Reconnect and try saving again."
        TaskFailureReason.INVALID_RESPONSE -> "Some planning details are invalid. Check the dates and deadlines."
        null -> "Future Planning could not complete this request."
    }

    class Factory(private val repository: FuturePlanningRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FuturePlanningViewModel(repository) as T
    }
}
