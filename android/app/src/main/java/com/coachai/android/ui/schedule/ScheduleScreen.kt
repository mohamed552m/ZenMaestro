package com.coachai.android.ui.schedule

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coachai.android.ui.theme.Accent
import com.coachai.android.ui.theme.AccentLine
import com.coachai.android.ui.theme.AccentSoft
import com.coachai.android.ui.theme.AccentText
import com.coachai.android.ui.theme.Amber
import com.coachai.android.ui.theme.Background
import com.coachai.android.ui.theme.Border
import com.coachai.android.ui.theme.BorderSoft
import com.coachai.android.ui.theme.Card
import com.coachai.android.ui.theme.Danger
import com.coachai.android.ui.theme.HeroEnd
import com.coachai.android.ui.theme.HeroStart
import com.coachai.android.ui.theme.NavigationSurface
import com.coachai.android.ui.theme.Raised
import com.coachai.android.ui.theme.Success
import com.coachai.android.ui.theme.TextMuted
import com.coachai.android.ui.theme.TextPrimary
import com.coachai.android.ui.theme.TextSubtle
import com.coachai.android.data.remote.PlanningDraftBundle
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

internal enum class TaskState { Pending, Running, Paused, Done, Failed }
private enum class AddTab { Ai, Voice, Manual }
private enum class Sheet { Add, Settings, Manage }

internal data class ScheduleTask(
    val id: Int,
    val title: String,
    val durationMinutes: Int,
    val remainingSeconds: Int = durationMinutes * 60,
    val priority: String = "Medium",
    val category: String = "Uncategorised",
    val fixedTime: String? = null,
    val scheduledTime: String? = null,
    val notes: String = "",
    val isBreak: Boolean = false,
    val state: TaskState = TaskState.Pending,
    val pauseCount: Int = 0,
    val failureReason: String = "",
    val version: Int = 0,
    val planId: Long? = null,
)

class ScheduleSessionState {
    internal val tasks = mutableStateListOf<ScheduleTask>()
    internal var nextId by mutableIntStateOf(1)
    internal var hasLocalChanges by mutableStateOf(false)
    internal var planId by mutableStateOf<Long?>(null)
    internal var scheduleRevision by mutableIntStateOf(0)
    internal var lastScheduleOperationId by mutableStateOf<String?>(null)

    internal fun syncFromHome(homeTasks: List<com.coachai.android.domain.HomeTask>, remotePlanId: Long?) {
        if (hasLocalChanges) return
        planId = remotePlanId
        tasks.clear()
        tasks.addAll(homeTasks.map { task ->
            ScheduleTask(
                id = task.id.toInt(),
                title = task.title,
                durationMinutes = task.estimatedMinutes,
                priority = when (task.priority) { 1 -> "Critical"; 2 -> "High"; 3 -> "Medium"; else -> "Low" },
                fixedTime = task.scheduledStart?.take(5).takeIf { task.isFixedTime },
                scheduledTime = task.scheduledStart?.take(5),
                isBreak = task.isBreak,
                state = when (task.status) {
                    com.coachai.android.domain.TaskStatus.PENDING -> TaskState.Pending
                    com.coachai.android.domain.TaskStatus.IN_PROGRESS -> TaskState.Running
                    com.coachai.android.domain.TaskStatus.COMPLETED -> TaskState.Done
                    com.coachai.android.domain.TaskStatus.FAILED -> TaskState.Failed
                },
                version = task.version,
                planId = task.planId,
            )
        })
        nextId = (tasks.maxOfOrNull { it.id } ?: 0) + 1
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    modifier: Modifier = Modifier,
    onNavigateHome: () -> Unit = {},
    onNavigateCoach: () -> Unit = {},
    onNavigateMore: () -> Unit = {},
    onOpenFuturePlanning: () -> Unit = {},
    displayName: String? = null,
    sessionState: ScheduleSessionState = remember { ScheduleSessionState() },
    syncViewModel: ScheduleSyncViewModel? = null,
) {
    val todayTasks = sessionState.tasks
    val futureTasks = remember { mutableStateListOf<ScheduleTask>() }
    val deferred = remember { mutableStateListOf<ScheduleTask>() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    var selectedDate by remember { mutableStateOf(today) }
    var weekOffset by remember { mutableIntStateOf(0) }
    var futurePlanId by remember { mutableStateOf<Long?>(null) }
    var futureScheduleRevision by remember { mutableIntStateOf(0) }
    var loadingSelectedDay by remember { mutableStateOf(false) }
    val tasks = if (selectedDate == today) todayTasks else futureTasks
    var activeSheet by remember { mutableStateOf<Sheet?>(null) }
    var addTab by remember { mutableStateOf(AddTab.Manual) }
    var addingBreak by remember { mutableStateOf(false) }
    var selectedTaskId by remember { mutableStateOf<Int?>(null) }
    var focusTaskId by remember { mutableStateOf<Int?>(null) }
    var workStart by remember { mutableStateOf("09:00") }
    var appliedWorkStart by remember { mutableStateOf<String?>(null) }
    var previousSchedule by remember { mutableStateOf<List<ScheduleTask>?>(null) }
    var plannerInsight by remember { mutableStateOf<String?>(null) }
    var reviewMessage by remember { mutableStateOf<String?>(null) }
    var scheduleWriteInFlight by remember { mutableStateOf(false) }
    val activeTimerTask = tasks.firstOrNull { it.state == TaskState.Running || it.state == TaskState.Paused }
    val focusNotificationVisible = rememberFocusTimerNotification(activeTimerTask)

    fun selectedPlanId(): Long? = if (selectedDate == today) sessionState.planId else futurePlanId
    fun selectedRevision(): Int = if (selectedDate == today) sessionState.scheduleRevision else futureScheduleRevision
    fun setSelectedPlan(planId: Long, revision: Int) {
        if (selectedDate == today) {
            sessionState.planId = planId
            sessionState.scheduleRevision = revision
        } else {
            futurePlanId = planId
            futureScheduleRevision = revision
        }
    }
    fun markSelectedChanged() {
        if (selectedDate == today) sessionState.hasLocalChanges = true
    }

    fun replaceTask(updated: ScheduleTask) {
        val index = tasks.indexOfFirst { it.id == updated.id }
        if (index >= 0) tasks[index] = updated
    }

    fun replaceSelectedTasks(remoteTasks: List<com.coachai.android.domain.HomeTask>) {
        tasks.clear()
        tasks.addAll(remoteTasks.map { task ->
            ScheduleTask(
                id = task.id.toInt(),
                title = task.title,
                durationMinutes = task.estimatedMinutes,
                priority = when (task.priority) { 1 -> "Critical"; 2 -> "High"; 3 -> "Medium"; else -> "Low" },
                fixedTime = task.scheduledStart?.take(5).takeIf { task.isFixedTime },
                scheduledTime = task.scheduledStart?.take(5),
                isBreak = task.isBreak,
                state = when (task.status) {
                    com.coachai.android.domain.TaskStatus.PENDING -> TaskState.Pending
                    com.coachai.android.domain.TaskStatus.IN_PROGRESS -> TaskState.Running
                    com.coachai.android.domain.TaskStatus.COMPLETED -> TaskState.Done
                    com.coachai.android.domain.TaskStatus.FAILED -> TaskState.Failed
                },
                version = task.version,
                planId = task.planId,
            )
        })
        sessionState.nextId = max(sessionState.nextId, (tasks.maxOfOrNull { it.id } ?: 0) + 1)
    }

    fun message(text: String, undo: (() -> Unit)? = null) {
        scope.launch {
            val result = snackbar.showSnackbar(
                message = text,
                actionLabel = if (undo == null) null else "Undo",
                duration = if (undo == null) SnackbarDuration.Short else SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }

    fun reloadSelectedPlan(onLoaded: () -> Unit = {}, onFinished: () -> Unit = {}) {
        val requestedDate = selectedDate
        val sync = syncViewModel ?: run {
            onFinished()
            return
        }
        sync.loadPlanDay(requestedDate) { result ->
            // Do not let a late response overwrite a different day the user
            // has already selected.
            if (selectedDate != requestedDate) {
                onFinished()
                return@loadPlanDay
            }
            result.onSuccess { day ->
                replaceSelectedTasks(day.tasks)
                day.planId?.let { setSelectedPlan(it, day.scheduleRevision) }
                onLoaded()
            }.onFailure { message(ScheduleSyncViewModel.message(it)) }
            onFinished()
        }
    }

    fun addTask(
        title: String,
        duration: Int,
        priority: String,
        category: String,
        fixedTime: String?,
        notes: String,
        isBreak: Boolean = false,
        onSaved: () -> Unit = {},
    ) {
        if (selectedDate < today) {
            message("Past plans are read-only.")
            return
        }
        val local = ScheduleTask(
            id = sessionState.nextId++,
            title = title,
            durationMinutes = duration,
            priority = priority,
            category = category,
            fixedTime = fixedTime,
            notes = notes,
            isBreak = isBreak,
            planId = selectedPlanId(),
        )
        tasks += local
        markSelectedChanged()
        scheduleTasks(tasks, workStart)
        message("${if (isBreak) "Break" else "Task"} added with a $duration-minute timer.")
        fun saveToPlan(planId: Long) {
            val taskForPlan = local.copy(planId = planId)
            replaceTask(taskForPlan)
            syncViewModel?.create(planId, taskForPlan.toHomeTask(), notes, isBreak) { result ->
                result.onSuccess { remote ->
                    // The server assigns the durable task id. Replace the row by
                    // its temporary local id before switching to that remote id;
                    // otherwise Start / Complete / More keep targeting a task
                    // that never existed on the server.
                    val localIndex = temporaryTaskIndex(tasks, local.id)
                    if (localIndex >= 0) {
                        tasks[localIndex] = local.mergeRemote(remote, TaskState.Pending)
                    }
                    // Creating a task changes the complete day. Reload the
                    // authoritative revision, reschedule every movable task
                    // from the chosen day start, then reload so hidden/stale
                    // rows cannot make a 09:00 plan appear to start at 10:00.
                    syncViewModel.loadPlanDay(selectedDate) { loaded ->
                        loaded.onSuccess { day ->
                            val durablePlanId = day.planId ?: planId
                            setSelectedPlan(durablePlanId, day.scheduleRevision)
                            syncViewModel.schedule(durablePlanId, day.scheduleRevision, workStart) { scheduled ->
                                scheduled.onSuccess { applied ->
                                    setSelectedPlan(durablePlanId, applied.scheduleRevision)
                                    sessionState.lastScheduleOperationId = applied.operationId
                                    syncViewModel.loadPlanDay(selectedDate) { refreshed ->
                                        refreshed.onSuccess { current ->
                                            replaceSelectedTasks(current.tasks)
                                            setSelectedPlan(durablePlanId, current.scheduleRevision)
                                            appliedWorkStart = workStart
                                            plannerInsight = "Schedule built from ${displayTime(workStart)}."
                                            onSaved()
                                        }.onFailure { message(ScheduleSyncViewModel.message(it)) }
                                    }
                                }.onFailure { message(ScheduleSyncViewModel.message(it)) }
                            }
                        }.onFailure { message(ScheduleSyncViewModel.message(it)) }
                    }
                }
                    .onFailure { tasks.removeAll { it.id == local.id }; message(ScheduleSyncViewModel.message(it)) }
            }
        }
        val planId = selectedPlanId()
        if (syncViewModel != null && planId != null) {
            saveToPlan(planId)
        } else if (syncViewModel != null) {
            syncViewModel.ensurePlanDate(selectedDate) { result ->
                result.onSuccess { target ->
                    setSelectedPlan(target.planId, target.scheduleRevision)
                    saveToPlan(target.planId)
                }.onFailure {
                    tasks.removeAll { item -> item.id == local.id }
                    message(ScheduleSyncViewModel.message(it))
                }
            }
        } else {
            onSaved()
        }
    }

    fun runSmartScheduler(closeSettings: Boolean = false) {
        if (selectedDate < today) {
            message("Past plans are read-only.")
            return
        }
        val sync = syncViewModel
        if (sync == null) {
            previousSchedule = tasks.toList()
            scheduleTasks(tasks, workStart)
            appliedWorkStart = workStart
            plannerInsight = "Schedule built from ${displayTime(workStart)}."
            if (closeSettings) activeSheet = null
            message("Schedule built from ${displayTime(workStart)}.")
            return
        }
        if (scheduleWriteInFlight) {
            message("A schedule change is already being saved.")
            return
        }
        scheduleWriteInFlight = true
        val before = tasks.toList()
        sync.ensurePlanDate(selectedDate) { targetResult ->
            targetResult.onSuccess { target ->
                setSelectedPlan(target.planId, target.scheduleRevision)
                sync.schedule(target.planId, target.scheduleRevision, workStart) { result ->
                    result.onSuccess { applied ->
                        previousSchedule = before
                        setSelectedPlan(target.planId, applied.scheduleRevision)
                        sessionState.lastScheduleOperationId = applied.operationId
                        reloadSelectedPlan(
                            onLoaded = {
                                appliedWorkStart = workStart
                                plannerInsight = "Schedule built from ${displayTime(workStart)}."
                                if (closeSettings) activeSheet = null
                                message("Schedule saved. Your day starts at ${displayTime(workStart)}.")
                            },
                            onFinished = { scheduleWriteInFlight = false },
                        )
                    }.onFailure {
                        scheduleWriteInFlight = false
                        message(ScheduleSyncViewModel.message(it))
                    }
                }
            }.onFailure {
                scheduleWriteInFlight = false
                message(ScheduleSyncViewModel.message(it))
            }
        }
    }

    fun finishTask(task: ScheduleTask, failed: Boolean = false, reason: String = "") {
        val before = task
        replaceTask(
            task.copy(
                state = if (failed) TaskState.Failed else TaskState.Done,
                failureReason = reason,
            ),
        )
        focusTaskId = null
        message(
            if (failed) "Failure logged — your coach will factor this in."
            else if (task.isBreak) "Break complete — welcome back."
            else "Task completed — nice work.",
        )
        syncViewModel?.command(
            before.toHomeTask(), if (failed) "fail" else "complete", reason.takeIf { failed },
        ) { result ->
            result.onSuccess { replaceTask(task.mergeRemote(it, if (failed) TaskState.Failed else TaskState.Done)) }
                .onFailure { replaceTask(before); message(ScheduleSyncViewModel.message(it)) }
        }
    }

    LaunchedEffect(tasks.map { Triple(it.id, it.state, it.remainingSeconds) }) {
        delay(1_000)
        tasks.filter { it.state == TaskState.Running }.forEach { task ->
            val remaining = max(0, task.remainingSeconds - 1)
            if (remaining == 0) finishTask(task.copy(remainingSeconds = 0))
            else replaceTask(task.copy(remainingSeconds = remaining))
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Background)) {
        val tablet = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (tablet) AppNavigationRail(onNavigateHome, onNavigateCoach, onNavigateMore)
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = Background,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {},
            ) { padding ->
                ScheduleContent(
                    modifier = Modifier.padding(padding),
                    tasks = tasks,
                    selectedDate = selectedDate,
                    weekOffset = weekOffset,
                    loadingSelectedDay = loadingSelectedDay,
                    displayName = displayName,
                    appliedWorkStart = appliedWorkStart,
                    onOpenFuturePlanning = onOpenFuturePlanning,
                    onWeekShift = { delta -> weekOffset = shiftedWeekOffset(weekOffset, delta) },
                    onDaySelected = { date ->
                        if (date < today) {
                            message("Planning starts from today.")
                        } else if (date != selectedDate && !loadingSelectedDay) {
                            selectedDate = date
                            plannerInsight = null
                            reviewMessage = null
                            if (date == today) {
                                message("Showing today's plan.")
                            } else {
                            loadingSelectedDay = true
                            syncViewModel?.loadPlanDay(date) { result ->
                                loadingSelectedDay = false
                                result.onSuccess { day ->
                                    futureTasks.clear()
                                    futureTasks.addAll(day.tasks.map { task ->
                                        ScheduleTask(
                                            id = task.id.toInt(),
                                            title = task.title,
                                            durationMinutes = task.estimatedMinutes,
                                            priority = when (task.priority) { 1 -> "Critical"; 2 -> "High"; 3 -> "Medium"; else -> "Low" },
                                            fixedTime = task.scheduledStart?.take(5).takeIf { task.isFixedTime },
                                            scheduledTime = task.scheduledStart?.take(5),
                                            isBreak = task.isBreak,
                                            state = when (task.status) {
                                                com.coachai.android.domain.TaskStatus.PENDING -> TaskState.Pending
                                                com.coachai.android.domain.TaskStatus.IN_PROGRESS -> TaskState.Running
                                                com.coachai.android.domain.TaskStatus.COMPLETED -> TaskState.Done
                                                com.coachai.android.domain.TaskStatus.FAILED -> TaskState.Failed
                                            },
                                            version = task.version,
                                            planId = task.planId,
                                        )
                                    })
                                    futurePlanId = day.planId
                                    futureScheduleRevision = day.scheduleRevision
                                    message(if (day.tasks.isEmpty()) "No plan yet for ${date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))}. Add tasks to plan ahead." else "Loaded ${date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))}.")
                                }.onFailure { message(ScheduleSyncViewModel.message(it)) }
                            }
                            }
                        }
                    },
                    plannerInsight = plannerInsight,
                    reviewMessage = reviewMessage,
                    onOpenAdd = { tab, isBreak ->
                        if (selectedDate < today) {
                            message("Past plans are read-only.")
                        } else {
                            addTab = tab
                            addingBreak = isBreak
                            activeSheet = Sheet.Add
                        }
                    },
                    onOpenSettings = { activeSheet = Sheet.Settings },
                    onReplan = {
                        if (selectedDate < today) message("Past plans are read-only.") else runSmartScheduler()
                    },
                    onComplete = { task ->
                        if (selectedDate != today) {
                            message("Tasks can only be completed on their scheduled day.")
                        } else if (task.state == TaskState.Done) {
                            message("Completed tasks stay completed in your saved history.")
                        } else finishTask(task)
                    },
                    onStart = { task ->
                        if (selectedDate != today) {
                            message("Focus sessions start from today's plan.")
                        } else {
                        val before = task
                        val updated = if (task.state == TaskState.Running) {
                            task.copy(state = TaskState.Paused, pauseCount = task.pauseCount + 1)
                        } else task.copy(state = TaskState.Running)
                        replaceTask(updated)
                        focusTaskId = task.id
                        val command = when (task.state) {
                            TaskState.Pending -> "start"
                            TaskState.Running -> "pause"
                            TaskState.Paused -> "resume"
                            else -> null
                        }
                        if (command != null) syncViewModel?.command(before.toHomeTask(), command) { result ->
                            result.onSuccess { replaceTask(updated.mergeRemote(it, updated.state)) }
                                .onFailure { replaceTask(before); focusTaskId = null; message(ScheduleSyncViewModel.message(it)) }
                        }
                        }
                    },
                    onMore = { task -> selectedTaskId = task.id; activeSheet = Sheet.Manage },
                )
            }
        }

        if (focusTaskId != null) {
            tasks.firstOrNull { it.id == focusTaskId }?.let { task ->
                FocusMode(
                    task = task,
                    notificationVisible = focusNotificationVisible,
                    onMinimize = {
                        focusTaskId = null
                        message("The timer will stay visible while you use other apps.")
                    },
                    onPauseResume = {
                        val updated = if (task.state == TaskState.Paused) task.copy(state = TaskState.Running)
                        else task.copy(state = TaskState.Paused, pauseCount = task.pauseCount + 1)
                        replaceTask(updated)
                        syncViewModel?.command(task.toHomeTask(), if (task.state == TaskState.Paused) "resume" else "pause") { result ->
                            result.onSuccess { replaceTask(updated.mergeRemote(it, updated.state)) }
                                .onFailure { replaceTask(task); message(ScheduleSyncViewModel.message(it)) }
                        }
                    },
                    onComplete = { finishTask(task) },
                )
            }
        }
    }

    when (activeSheet) {
        Sheet.Add -> AddTaskSheet(
            initialTab = addTab,
            isBreak = addingBreak,
            targetDate = selectedDate,
            currentMinutes = tasks.sumOf { it.durationMinutes },
            onDismiss = { activeSheet = null },
            onAdd = { title, duration, priority, category, fixedTime, notes, isBreak ->
                addTask(title, duration, priority, category, fixedTime, notes, isBreak)
                activeSheet = null
            },
            onGenerated = { draft, source ->
                val sync = syncViewModel
                if (sync == null) {
                    message("Reconnect to save this reviewed plan.")
                } else if (selectedDate != today) {
                    var remainingToSave = draft.tasks.size
                    draft.tasks.forEach { item ->
                        addTask(
                            item.title,
                            item.minutes,
                            when (item.priority) { 1 -> "Critical"; 2 -> "High"; 3 -> "Medium"; else -> "Low" },
                            item.category,
                            item.fixedStart,
                            item.description,
                        ) {
                            remainingToSave -= 1
                            if (remainingToSave == 0) {
                                plannerInsight = "✦ $source saved ${draft.tasks.size} future task(s) and applied Smart Scheduling."
                                activeSheet = null
                                runSmartScheduler()
                            }
                        }
                    }
                } else {
                    sync.applyDraft(draft) { result ->
                        result.onSuccess { applied ->
                            sessionState.planId = applied.planId
                            sessionState.scheduleRevision = applied.scheduleRevision
                            val savedTasks = draft.tasks.mapIndexed { index, item ->
                                ScheduleTask(
                                    id = applied.taskIds[index].toInt(),
                                    title = item.title,
                                    durationMinutes = item.minutes,
                                    priority = when (item.priority) {
                                        1 -> "Critical"; 2 -> "High"; 3 -> "Medium"; else -> "Low"
                                    },
                                    category = item.category,
                                    fixedTime = item.fixedStart,
                                    scheduledTime = item.fixedStart,
                                    notes = item.description,
                                    isBreak = applied.taskBreaks[index],
                                    planId = applied.planId,
                                )
                            }
                            tasks.addAll(savedTasks)
                            sessionState.nextId = (tasks.maxOfOrNull { it.id } ?: 0) + 1
                            sessionState.hasLocalChanges = true
                            plannerInsight = "✦ $source saved ${draft.tasks.size} task(s) together and is applying Smart Scheduling."
                            val reviews = draft.tasks.count { it.needsReview }
                            reviewMessage = if (reviews > 0) "$reviews task(s) still need your review." else null
                            activeSheet = null
                            runSmartScheduler()
                        }.onFailure { message(ScheduleSyncViewModel.message(it)) }
                    }
                }
            },
            onMessage = { message(it) },
            onGenerateAi = { input, done ->
                val sync = syncViewModel
                if (sync == null) done(Result.failure(IllegalStateException()))
                else sync.draft(input, done)
            },
            onTranscribe = { audio, mimeType, done ->
                val sync = syncViewModel
                if (sync == null) done(Result.failure(IllegalStateException()))
                else sync.transcribe(audio, mimeType, done)
            },
        )
        Sheet.Settings -> SchedulerSheet(
            workStart = workStart,
            breaks = tasks.filter { it.isBreak },
            previousScheduleAvailable = previousSchedule != null,
            onDismiss = { activeSheet = null },
            onWorkStartChanged = { workStart = it },
            onRunScheduler = {
                runSmartScheduler(closeSettings = true)
            },
            onUndoSchedule = {
                val operationId = sessionState.lastScheduleOperationId
                val old = previousSchedule
                if (scheduleWriteInFlight) {
                    message("A schedule change is already being saved.")
                } else if (syncViewModel != null && operationId != null && old != null) {
                    scheduleWriteInFlight = true
                    syncViewModel.undoSchedule(operationId) { result ->
                        result.onSuccess { revision ->
                            selectedPlanId()?.let { setSelectedPlan(it, revision) }
                            reloadSelectedPlan(
                                onLoaded = {
                                    previousSchedule = null
                                    sessionState.lastScheduleOperationId = null
                                    message("The complete previous schedule was restored.")
                                },
                                onFinished = { scheduleWriteInFlight = false },
                            )
                        }.onFailure {
                            scheduleWriteInFlight = false
                            message(ScheduleSyncViewModel.message(it))
                        }
                    }
                } else {
                    message("No saved scheduling operation is available to undo.")
                }
            },
            onAddBreak = { activeSheet = null; addingBreak = true; addTab = AddTab.Manual; activeSheet = Sheet.Add },
            onRemoveBreak = { task ->
                val before = tasks.toList()
                tasks.removeAll { it.id == task.id }
                message("Break removed.")
                syncViewModel?.delete(task.toHomeTask()) { result ->
                    result.onFailure {
                        tasks.clear(); tasks.addAll(before)
                        message(ScheduleSyncViewModel.message(it))
                    }
                }
            },
        )
        Sheet.Manage -> tasks.firstOrNull { it.id == selectedTaskId }?.let { task ->
            ManageTaskSheet(
                task = task,
                onDismiss = { activeSheet = null },
                onMove = { time ->
                    val conflict = tasks.firstOrNull {
                        it.id != task.id && it.fixedTime != null && overlaps(time, task.durationMinutes, it.fixedTime, it.durationMinutes)
                    }
                    if (task.fixedTime != null) message("Fixed tasks cannot be manually moved. Delete and recreate it if the time is wrong.")
                    else if (conflict != null) message("That move conflicts with ${conflict.title}. Choose another time.")
                    else {
                        val before = task
                        val sync = syncViewModel
                        if (sync == null) {
                            replaceTask(task.copy(scheduledTime = time))
                            activeSheet = null
                            message("${task.title} moved in the preview.")
                        } else if (scheduleWriteInFlight) {
                            message("A schedule change is already being saved.")
                        } else {
                            scheduleWriteInFlight = true
                            sync.ensurePlanDate(selectedDate) { targetResult ->
                                targetResult.onSuccess { target ->
                                    sync.move(task.id.toLong(), target.scheduleRevision, time) { result ->
                                        result.onSuccess { moved ->
                                            setSelectedPlan(target.planId, moved.scheduleRevision)
                                            sessionState.lastScheduleOperationId = moved.operationId
                                            previousSchedule = tasks.toList()
                                            reloadSelectedPlan(
                                                onLoaded = {
                                                    activeSheet = null
                                                    message("${task.title} moved and saved.")
                                                },
                                                onFinished = { scheduleWriteInFlight = false },
                                            )
                                        }.onFailure {
                                            scheduleWriteInFlight = false
                                            message(ScheduleSyncViewModel.message(it))
                                        }
                                    }
                                }.onFailure {
                                    scheduleWriteInFlight = false
                                    message(ScheduleSyncViewModel.message(it))
                                }
                            }
                        }
                    }
                },
                onFailed = { reason -> activeSheet = null; finishTask(task, true, reason) },
                onDefer = {
                    val before = tasks.toList()
                    deferred += task
                    tasks.removeAll { it.id == task.id }
                    activeSheet = null
                    message("${task.title} moved to tomorrow.")
                    syncViewModel?.command(task.toHomeTask(), "defer") { result ->
                        result.onFailure { tasks.clear(); tasks.addAll(before); deferred.remove(task); message(ScheduleSyncViewModel.message(it)) }
                    }
                },
                onDelete = {
                    val before = tasks.toList()
                    tasks.removeAll { it.id == task.id }
                    activeSheet = null
                    message("${task.title} deleted.")
                    syncViewModel?.delete(task.toHomeTask()) { result ->
                        result.onFailure { tasks.clear(); tasks.addAll(before); message(ScheduleSyncViewModel.message(it)) }
                    }
                },
            )
        }
        null -> Unit
    }
}

internal fun ScheduleTask.toHomeTask(): com.coachai.android.domain.HomeTask =
    com.coachai.android.domain.HomeTask(
        id = id.toLong(),
        title = title,
        scheduledStart = scheduledTime ?: fixedTime,
        estimatedMinutes = durationMinutes,
        priority = when (priority.lowercase()) {
            "critical" -> 1
            "high" -> 2
            "medium" -> 3
            else -> 5
        },
        status = when (state) {
            TaskState.Pending, TaskState.Paused -> com.coachai.android.domain.TaskStatus.PENDING
            TaskState.Running -> com.coachai.android.domain.TaskStatus.IN_PROGRESS
            TaskState.Done -> com.coachai.android.domain.TaskStatus.COMPLETED
            TaskState.Failed -> com.coachai.android.domain.TaskStatus.FAILED
        },
        isFixedTime = fixedTime != null,
        planId = planId,
        version = version,
        isBreak = isBreak,
    )

private fun ScheduleTask.mergeRemote(remote: com.coachai.android.domain.HomeTask, desiredState: TaskState) = copy(
    id = remote.id.toInt(),
    title = remote.title,
    durationMinutes = remote.estimatedMinutes,
    priority = when (remote.priority) { 1 -> "Critical"; 2 -> "High"; 3 -> "Medium"; else -> "Low" },
    scheduledTime = remote.scheduledStart?.take(5) ?: scheduledTime,
    fixedTime = if (remote.isFixedTime) remote.scheduledStart?.take(5) else null,
    isBreak = remote.isBreak,
    state = desiredState,
    version = remote.version,
    planId = remote.planId,
)

@Composable
private fun ScheduleContent(
    modifier: Modifier,
    tasks: List<ScheduleTask>,
    selectedDate: LocalDate,
    weekOffset: Int,
    loadingSelectedDay: Boolean,
    displayName: String?,
    appliedWorkStart: String?,
    onOpenFuturePlanning: () -> Unit,
    onWeekShift: (Int) -> Unit,
    onDaySelected: (LocalDate) -> Unit,
    plannerInsight: String?,
    reviewMessage: String?,
    onOpenAdd: (AddTab, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onReplan: () -> Unit,
    onComplete: (ScheduleTask) -> Unit,
    onStart: (ScheduleTask) -> Unit,
    onMore: (ScheduleTask) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0x1AFF7A45), Background), radius = 700f),
        ),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { BrandHeader(hasPlan = tasks.isNotEmpty(), selectedDate = selectedDate, displayName = displayName) }
        item { ScheduleHeading(selectedDate) { onOpenAdd(AddTab.Manual, false) } }
        item { WeekSelector(selectedDate, weekOffset, loadingSelectedDay, onWeekShift, onDaySelected) }
        if (selectedDate >= LocalDate.now()) item { FuturePlanningEntry(onOpenFuturePlanning) }
        item { TodaySummary(tasks, selectedDate, appliedWorkStart) }
        if (tasks.isEmpty()) {
            item {
                EmptySchedule(
                    selectedDate = selectedDate,
                    onAi = { onOpenAdd(AddTab.Ai, false) },
                    onVoice = { onOpenAdd(AddTab.Voice, false) },
                    onManual = { onOpenAdd(AddTab.Manual, false) },
                    onBreak = { onOpenAdd(AddTab.Manual, true) },
                    onSettings = onOpenSettings,
                )
            }
        } else {
            plannerInsight?.let { item { NoticeCard(it, Success) } }
            reviewMessage?.let { item { NoticeCard("⚠ $it", Amber) } }
            item {
                if (selectedDate < LocalDate.now()) {
                    Column(Modifier.padding(top = 8.dp)) {
                        Eyebrow("READ-ONLY HISTORY")
                        Text("Past day", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    SectionHeader(
                        onSettings = onOpenSettings,
                        onBreak = { onOpenAdd(AddTab.Manual, true) },
                        onReplan = onReplan,
                    )
                }
            }
            items(tasks, key = { it.id }) { task ->
                TaskTimelineCard(
                    task,
                    onComplete,
                    onStart,
                    if (selectedDate < LocalDate.now()) ({}) else ({ onMore(task) }),
                )
            }
            if (scheduleDateSelectable(selectedDate, LocalDate.now())) {
                item {
                    OutlinedButton(
                        onClick = { onOpenAdd(AddTab.Manual, false) },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(15.dp),
                        border = BorderStroke(1.dp, AccentLine),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentText),
                    ) {
                        Text("+  Add another task", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
            item { SchedulerNote() }
            item { DistributionCard(tasks) }
            if (tasks.any { it.pauseCount > 0 }) item { FocusPatternCard(tasks) }
        }
    }
}

@Composable
private fun FuturePlanningEntry(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(containerColor = AccentSoft),
        border = BorderStroke(1.dp, AccentLine),
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Accent),
                contentAlignment = Alignment.Center,
            ) { Text("↗", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold) }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("Plan deadlines across days", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Text("Choose tasks, preview the load, then save", color = TextMuted, fontSize = 12.sp)
            }
            Text("Open  ›", color = AccentText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BrandHeader(hasPlan: Boolean, selectedDate: LocalDate, displayName: String?) {
    val initials = displayName.orEmpty().trim().split(Regex("\\s+")).filter(String::isNotBlank)
        .take(2).joinToString("") { it.take(1).uppercase() }.ifBlank { "ZM" }
    Column(Modifier.statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Accent), contentAlignment = Alignment.Center) {
                Text("C", color = Color(0xFF1C110C), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text("Zen Maestro", Modifier.padding(start = 10.dp).weight(1f), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            Box(Modifier.size(34.dp).clip(CircleShape).background(AccentSoft).border(1.dp, AccentLine, CircleShape), contentAlignment = Alignment.Center) {
                Text(initials, color = AccentText, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(Accent))
            Text(
                when {
                    selectedDate > LocalDate.now() && hasPlan -> "SCHEDULE · FUTURE PLAN"
                    selectedDate > LocalDate.now() -> "SCHEDULE · PLAN AHEAD"
                    hasPlan -> "SCHEDULE · LIVE DAY PLAN"
                    else -> "SCHEDULE · NO PLAN YET"
                },
                Modifier.padding(start = 7.dp, bottom = 5.dp), color = TextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ScheduleHeading(selectedDate: LocalDate, onAdd: () -> Unit) {
    val isToday = selectedDate == LocalDate.now()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Eyebrow(selectedDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH)).uppercase())
            Text(if (isToday) "Today's schedule" else "Plan ahead", style = MaterialTheme.typography.displaySmall)
            Text(if (isToday) "Your plan, timed and tracked in real time." else "Prepare this day now and adjust it anytime before it starts.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (selectedDate >= LocalDate.now()) {
            Button(
                onClick = onAdd,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF1C110C)),
            ) { Text("+", fontSize = 24.sp) }
        }
    }
}

@Composable
private fun WeekSelector(selectedDate: LocalDate, weekOffset: Int, loading: Boolean, onWeekShift: (Int) -> Unit, onDaySelected: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val anchor = today.plusWeeks(weekOffset.toLong())
    val days = (-3L..3L).map(anchor::plusDays)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Card).border(1.dp, Border, RoundedCornerShape(17.dp)).padding(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = !loading && weekOffset > 0, onClick = { onWeekShift(-1) }) { Text("‹ Previous", color = TextMuted) }
            Text(anchor.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            TextButton(enabled = !loading && weekOffset < 12, onClick = { onWeekShift(1) }) { Text("Next ›", color = AccentText) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            days.forEach { date ->
                val active = selectedDate == date
                val selectable = scheduleDateSelectable(date, today)
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (active) Accent else Color.Transparent)
                        .clickable(enabled = !loading && selectable) { onDaySelected(date) }.padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val inactiveColor = if (selectable) TextMuted else TextMuted.copy(alpha = .35f)
                    Text(date.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)), color = if (active) Color(0xFF1B110D) else inactiveColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(date.dayOfMonth.toString(), color = if (active) Color(0xFF1B110D) else if (selectable) TextPrimary else TextMuted.copy(alpha = .35f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

internal fun scheduleDateSelectable(date: LocalDate, today: LocalDate): Boolean = date >= today

internal fun shiftedWeekOffset(current: Int, delta: Int): Int = (current + delta).coerceIn(0, 12)

internal fun temporaryTaskIndex(tasks: List<ScheduleTask>, temporaryId: Int): Int =
    tasks.indexOfFirst { it.id == temporaryId }

@Composable
private fun TodaySummary(tasks: List<ScheduleTask>, selectedDate: LocalDate, appliedWorkStart: String?) {
    val done = tasks.count { it.state == TaskState.Done }
    val failed = tasks.count { it.state == TaskState.Failed }
    val percent = if (tasks.isEmpty()) 0 else done * 100 / tasks.size
    val minutes = tasks.sumOf { it.durationMinutes }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = TextPrimary),
        border = BorderStroke(1.dp, Border), shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.background(Brush.linearGradient(listOf(HeroStart, Card, HeroEnd))).padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (selectedDate == LocalDate.now()) "Today at a glance" else "Planned day at a glance", style = MaterialTheme.typography.titleMedium)
                Text(if (tasks.isEmpty()) "Ready to plan" else "$percent% done", color = AccentText, style = MaterialTheme.typography.labelMedium)
            }
            appliedWorkStart?.let {
                Text(dayStartLabel(it), Modifier.padding(top = 5.dp), color = AccentText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 11.dp).height(6.dp).clip(CircleShape).background(Border)) {
                Box(Modifier.fillMaxWidth(percent / 100f).fillMaxHeight().background(Accent))
            }
            Row(Modifier.fillMaxWidth()) {
                SummaryStat(tasks.size.toString(), "Total items", Modifier.weight(1f))
                SummaryStat(formatMinutes(minutes), "Planned time", Modifier.weight(1f))
                SummaryStat("${tasks.count { it.scheduledTime != null || it.fixedTime != null }}/${tasks.size}", "Scheduled", Modifier.weight(1f))
                SummaryStat("$done / ${tasks.size}", "Completed", Modifier.weight(1f), if (failed > 0) "$failed failed" else null)
            }
        }
    }
}

@Composable
private fun SummaryStat(value: String, label: String, modifier: Modifier, warning: String? = null) {
    Column(modifier.padding(horizontal = 5.dp)) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(label, color = TextMuted, fontSize = 10.sp)
        warning?.let { Text(it, color = Danger, fontSize = 9.sp) }
    }
}

@Composable
private fun EmptySchedule(selectedDate: LocalDate, onAi: () -> Unit, onVoice: () -> Unit, onManual: () -> Unit, onBreak: () -> Unit, onSettings: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Card, contentColor = TextPrimary), border = BorderStroke(1.dp, Border), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(AccentSoft).border(1.dp, AccentLine, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                Text("✦", color = AccentText, fontSize = 28.sp)
            }
            Text(if (selectedDate == LocalDate.now()) "No plan for today." else "Nothing planned for this day yet.", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleLarge)
            Text(if (selectedDate < LocalDate.now()) "Past plans are kept read-only." else "Describe the day and let the AI Planner split it into tasks, use your voice, or add everything manually.", Modifier.padding(vertical = 8.dp), color = TextMuted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            if (selectedDate >= LocalDate.now()) PrimaryButton(if (selectedDate == LocalDate.now()) "✦ Plan my day with AI" else "✦ Plan ahead with AI", onAi)
            if (selectedDate >= LocalDate.now()) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    SmallOutlinedButton("◉ Use voice", Modifier.weight(1f), onVoice)
                    SmallOutlinedButton("＋ Add task", Modifier.weight(1f), onManual)
                    SmallOutlinedButton("☕ Add break", Modifier.weight(1f), onBreak)
                }
                SmallOutlinedButton("⚙ Scheduler & Google Calendar", Modifier.fillMaxWidth().padding(top = 8.dp), onSettings)
            }
            HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 12.dp), color = BorderSoft)
            Text("Nothing is scheduled without you. Review every title, duration and time before saving.", color = TextMuted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionHeader(onSettings: () -> Unit, onBreak: () -> Unit, onReplan: () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Eyebrow("TIMELINE")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Your day", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            SmallOutlinedButton("⚙ Schedule settings", onClick = onSettings)
            Spacer(Modifier.width(6.dp)); SmallOutlinedButton("☕ Break", onClick = onBreak)
            Spacer(Modifier.width(6.dp)); AccentOutlinedButton("✦ Re-plan", onReplan)
        }
    }
}

@Composable
private fun TaskTimelineCard(task: ScheduleTask, onComplete: (ScheduleTask) -> Unit, onStart: (ScheduleTask) -> Unit, onMore: (ScheduleTask) -> Unit) {
    val active = task.state == TaskState.Running
    val done = task.state == TaskState.Done
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.End) {
            Text((task.scheduledTime ?: task.fixedTime ?: "—").take(5), color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.padding(top = 6.dp, end = 2.dp).size(9.dp).clip(CircleShape).background(if (done) Success else if (active) Accent else TextSubtle))
        }
        Card(
            Modifier.weight(1f),
            colors = CardDefaults.cardColors(containerColor = if (active) AccentSoft.copy(alpha = .72f) else Card, contentColor = TextPrimary),
            border = BorderStroke(1.dp, if (active) AccentLine else Border), shape = RoundedCornerShape(15.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(9.dp)).background(Raised).border(1.dp, Border, RoundedCornerShape(9.dp)).clickable { onComplete(task) },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (task.isBreak) "☕" else "✓", color = if (done) Success else TextMuted) }
                    Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                        Text(task.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (done) TextMuted else TextPrimary)
                        Text(
                            if (task.isBreak) "${task.durationMinutes} min · Protected time"
                            else "${task.category} · ${task.durationMinutes} min · ${task.priority} · ${if (task.fixedTime == null) "Flexible" else "Fixed"}",
                            color = TextMuted, style = MaterialTheme.typography.bodySmall,
                        )
                        if (task.notes.isNotBlank()) Text(task.notes, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    StatusBadge(task.state)
                }
                HorizontalDivider(Modifier.padding(vertical = 9.dp), color = BorderSoft)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallOutlinedButton(if (done) "↶ Undo" else "✓ Complete", onClick = { onComplete(task) }, accent = !done)
                    if (!done && task.state != TaskState.Failed) SmallOutlinedButton(
                        if (active) "Ⅱ Pause" else if (task.state == TaskState.Paused) "▶ Resume" else "▶ Start",
                        onClick = { onStart(task) },
                    )
                    SmallOutlinedButton("More", onClick = { onMore(task) })
                    Spacer(Modifier.weight(1f))
                    if (task.state == TaskState.Running || task.state == TaskState.Paused) Text(formatClock(task.remainingSeconds), color = AccentText, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(state: TaskState) {
    val (label, color) = when (state) {
        TaskState.Pending -> "Pending" to TextMuted
        TaskState.Running -> "Active" to Accent
        TaskState.Paused -> "Paused" to Amber
        TaskState.Done -> "Done" to Success
        TaskState.Failed -> "Failed" to Danger
    }
    Box(Modifier.clip(CircleShape).background(color.copy(alpha = .1f)).border(1.dp, color.copy(alpha = .35f), CircleShape).padding(horizontal = 7.dp, vertical = 5.dp)) {
        Text(label.uppercase(), color = color, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun SchedulerNote() {
    Card(colors = CardDefaults.cardColors(containerColor = Card, contentColor = TextPrimary), border = BorderStroke(1.dp, Border), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(13.dp)) {
            Text("◇", color = Accent); Column(Modifier.padding(start = 10.dp)) {
                Text("Scheduler note", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Task durations stay intact, breaks stay protected, and a task can use up to three schedule windows when needed.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DistributionCard(tasks: List<ScheduleTask>) {
    val actualTasks = tasks.filterNot { it.isBreak }
    val levels = listOf("Low" to Color(0xFF7F8B96), "Medium" to Amber, "High" to Accent, "Critical" to Danger)
    Card(colors = CardDefaults.cardColors(containerColor = Card, contentColor = TextPrimary), border = BorderStroke(1.dp, Border), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(13.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("🥯 Task distribution", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("${actualTasks.size} task${if (actualTasks.size == 1) "" else "s"}", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(8.dp).clip(CircleShape).background(BorderSoft)) {
                levels.forEach { (level, color) ->
                    val count = actualTasks.count { it.priority == level }
                    if (count > 0) Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(color))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (actualTasks.isEmpty()) Text("No tasks yet — breaks are not counted.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                levels.forEach { (level, _) ->
                    val count = actualTasks.count { it.priority == level }
                    if (count > 0) Text("$level  $count", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun FocusPatternCard(tasks: List<ScheduleTask>) {
    val paused = tasks.filter { !it.isBreak && it.pauseCount > 0 }
    Card(colors = CardDefaults.cardColors(containerColor = Card, contentColor = TextPrimary), border = BorderStroke(1.dp, Border), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(13.dp)) {
            Text("📊 Your focus patterns", fontWeight = FontWeight.Bold)
            Text(
                if (paused.size < 3) "Not enough data yet — patterns need at least 3 started tasks before they mean anything."
                else "Pause patterns from your timer history: ${paused.sumOf { it.pauseCount }} pauses across ${paused.size} tasks.",
                Modifier.padding(top = 8.dp), color = TextMuted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun NoticeCard(text: String, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = .08f), contentColor = TextPrimary), border = BorderStroke(1.dp, color.copy(alpha = .3f)), shape = RoundedCornerShape(10.dp)) {
        Text(text, Modifier.padding(12.dp), color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FocusMode(
    task: ScheduleTask,
    notificationVisible: Boolean,
    onMinimize: () -> Unit,
    onPauseResume: () -> Unit,
    onComplete: () -> Unit,
) {
    val progress = 1f - task.remainingSeconds.toFloat() / (task.durationMinutes * 60).coerceAtLeast(1)
    Box(
        Modifier.fillMaxSize()
            .background(Background)
            .background(Brush.radialGradient(listOf(Color(0x24FF7A45), Color.Transparent), radius = 760f))
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("FOCUS SESSION")
                    Text("Stay with one thing", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(
                    onClick = onMinimize,
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Border),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Raised),
                ) { Text("⌄", fontSize = 20.sp, color = TextPrimary) }
            }

            Row(
                Modifier.padding(top = 18.dp).clip(CircleShape).background(Success.copy(alpha = .1f))
                    .border(1.dp, Success.copy(alpha = .3f), CircleShape).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Success))
                Text(
                    if (notificationVisible) "Timer active · notification visible"
                    else "Timer active · allow notifications to keep it visible",
                    Modifier.padding(start = 8.dp),
                    color = if (notificationVisible) Success else Amber,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 22.dp),
                colors = CardDefaults.cardColors(containerColor = Card, contentColor = TextPrimary),
                border = BorderStroke(1.dp, AccentLine.copy(alpha = .75f)),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("CURRENT TASK", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                    Text(
                        task.title,
                        Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.displaySmall.copy(
                            brush = Brush.horizontalGradient(listOf(TextPrimary, Accent)),
                            fontSize = 34.sp,
                            lineHeight = 40.sp,
                            fontWeight = FontWeight.ExtraBold,
                        ),
                        textAlign = TextAlign.Center,
                    )
                    Text("${task.category} · ${task.durationMinutes} minute session", Modifier.padding(top = 6.dp), color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatClock(task.remainingSeconds),
                        Modifier.padding(top = 34.dp),
                        color = TextPrimary,
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = 64.sp,
                            lineHeight = 68.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                    Text(
                        if (task.state == TaskState.Paused) "Paused" else "In progress",
                        Modifier.padding(top = 8.dp), color = AccentText,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Box(Modifier.fillMaxWidth().padding(top = 30.dp).height(8.dp).clip(CircleShape).background(BorderSoft)) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Accent))
                    }
                    Text("${formatMinutes((task.remainingSeconds + 59) / 60)} remaining", Modifier.fillMaxWidth().padding(top = 10.dp), color = TextMuted, textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallOutlinedButton(
                    if (task.state == TaskState.Paused) "▶ Resume" else "Ⅱ Pause",
                    Modifier.weight(1f).height(58.dp), onPauseResume,
                )
                Button(
                    onClick = onComplete,
                    modifier = Modifier.weight(1f).height(58.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF1B110D)),
                ) { Text("✓ Complete", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTaskSheet(
    initialTab: AddTab,
    isBreak: Boolean,
    targetDate: LocalDate,
    currentMinutes: Int,
    onDismiss: () -> Unit,
    onAdd: (String, Int, String, String, String?, String, Boolean) -> Unit,
    onGenerated: (PlanningDraftBundle, String) -> Unit,
    onMessage: (String) -> Unit,
    onGenerateAi: (String, (Result<PlanningDraftBundle>) -> Unit) -> Unit,
    onTranscribe: (ByteArray, String, (Result<String>) -> Unit) -> Unit,
) {
    var tab by remember(initialTab) { mutableStateOf(initialTab) }
    var title by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("30") }
    var priority by remember { mutableStateOf("Medium") }
    var category by remember { mutableStateOf("Uncategorised") }
    var fixed by remember { mutableStateOf(isBreak) }
    var fixedTime by remember { mutableStateOf(if (isBreak) "12:00" else "09:00") }
    var notes by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var transcript by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var manualCapacityWarning by remember { mutableStateOf(false) }
    var pendingDraft by remember { mutableStateOf<PlanningDraftBundle?>(null) }
    var aiGenerating by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var recordingStartedAt by remember { mutableStateOf(0L) }
    var recordingPeakAmplitude by remember { mutableIntStateOf(0) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val startRecording = {
        runCatching {
            player?.release()
            player = null
            playing = false
            val file = File(context.cacheDir, "voice-${UUID.randomUUID()}.m4a")
            val activeRecorder = createAudioRecorder(context, file)
            activeRecorder.start()
            recordingFile?.delete()
            recordingFile = file
            recorder = activeRecorder
            recordingStartedAt = SystemClock.elapsedRealtime()
            recordingPeakAmplitude = 0
            transcript = ""
            pendingDraft = null
            recording = true
        }.onFailure {
            recorder?.release()
            recorder = null
            recording = false
            onMessage("The microphone couldn't start. Check the permission and try again.")
        }
    }

    LaunchedEffect(recording) {
        while (recording) {
            delay(100)
            val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
            recordingPeakAmplitude = max(recordingPeakAmplitude, amplitude)
        }
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startRecording()
        else onMessage("Microphone permission is required for voice planning.")
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recorder?.stop() }
            recorder?.release()
            player?.release()
            recordingFile?.delete()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Card,
        contentColor = TextPrimary,
        dragHandle = { Box(Modifier.padding(vertical = 10.dp).size(38.dp, 4.dp).clip(CircleShape).background(Border)) },
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            contentPadding = PaddingValues(bottom = 52.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow(if (targetDate == LocalDate.now()) "ADD TO TODAY" else "PLAN ${targetDate.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)).uppercase()}")
                        Text(if (isBreak) "Add a break" else if (targetDate == LocalDate.now()) "Create today's plan" else "Plan ahead", style = MaterialTheme.typography.titleLarge)
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Text("×", fontSize = 24.sp, color = TextMuted) }
                }
            }
            if (!isBreak) {
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 15.dp).clip(RoundedCornerShape(12.dp)).background(Raised).padding(4.dp)) {
                        AddTab.entries.forEach { entry ->
                            val selected = tab == entry
                            Text(
                                when (entry) { AddTab.Ai -> "✦ AI planner"; AddTab.Voice -> "◉ Voice"; AddTab.Manual -> "✎ Manual" },
                                Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (selected) Card else Color.Transparent)
                                    .clickable { tab = entry }.padding(vertical = 14.dp),
                                color = if (selected) TextPrimary else TextMuted, textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold, fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
            when {
                isBreak || tab == AddTab.Manual -> item {
                    Column {
                        if (!isBreak) LabeledTextField("Task title", title, { title = it }, "What needs to get done?")
                        LabeledTextField(
                            "Duration",
                            duration,
                            { duration = it.filter(Char::isDigit).take(3) },
                            "30",
                            keyboardType = KeyboardType.Number,
                            suffix = "minutes",
                        )
                        if (!isBreak) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                OptionSelector("Priority", priority, listOf("Low", "Medium", "High", "Critical"), { priority = it }, Modifier.weight(1f))
                                OptionSelector("Category", category, listOf("Uncategorised", "Work", "Study", "Health", "Personal"), { category = it }, Modifier.weight(1f))
                            }
                            Text("Task timing", Modifier.padding(top = 13.dp, bottom = 7.dp), color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                ChoiceButton("Flexible", !fixed, Modifier.weight(1f)) { fixed = false }
                                ChoiceButton("Fixed time", fixed, Modifier.weight(1f)) { fixed = true }
                            }
                        }
                        if (fixed) TimeField(if (isBreak) "Break starts at" else "Fixed start time", fixedTime) { fixedTime = it }
                        if (!isBreak) LabeledTextField("Notes (optional)", notes, { notes = it }, "Add a little context…")
                        if (manualCapacityWarning) {
                            NoticeCard("🛡 Adding this item pushes today over your usual 4h capacity.", Amber)
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallOutlinedButton("Skip / Cancel", Modifier.weight(1f), onClick = { manualCapacityWarning = false })
                                SmallOutlinedButton("Add anyway", Modifier.weight(1f), onClick = {
                                    val mins = duration.toIntOrNull() ?: 30
                                    onAdd(if (isBreak) "Break" else title.trim(), mins, priority, category, if (fixed) fixedTime else null, notes.trim(), isBreak)
                                }, accent = true)
                            }
                        } else {
                            Spacer(Modifier.height(14.dp))
                            PrimaryButton(if (isBreak) "Add break to today" else "Add task to today") {
                                val mins = duration.toIntOrNull()
                                when {
                                    mins == null || mins !in 15..480 -> onMessage("Enter a duration from 15 to 480 minutes.")
                                    !isBreak && title.isBlank() -> onMessage("Add a task title first.")
                                    currentMinutes + mins > 240 -> manualCapacityWarning = true
                                    else -> onAdd(if (isBreak) "Break" else title.trim(), mins, priority, category, if (fixed) fixedTime else null, notes.trim(), isBreak)
                                }
                            }
                        }
                    }
                }
                tab == AddTab.Ai -> item {
                    Column {
                        LabeledTextField(
                            "What's on your mind for today?",
                            description,
                            {
                                description = it
                                pendingDraft = null
                            },
                            "Study for 90 minutes, gym for 45 minutes, finish the report…",
                            minLines = 4,
                        )
                        Spacer(Modifier.height(14.dp))
                        pendingDraft?.let { draft ->
                            PlanningDraftReview(
                                draft = draft,
                                onEditInput = { pendingDraft = null },
                                onShorten = {
                                    val total = draft.tasks.sumOf { it.minutes }.coerceAtLeast(1)
                                    pendingDraft = draft.copy(tasks = draft.tasks.map {
                                        it.copy(minutes = max(15, ((it.minutes * 180.0 / total) / 5).toInt() * 5))
                                    })
                                },
                                onSave = { onGenerated(pendingDraft ?: draft, "AI Planner") },
                            )
                        } ?: PrimaryButton(if (aiGenerating) "Generating…" else "Generate my plan") {
                            if (description.isBlank()) {
                                onMessage("Describe at least one task first.")
                            } else if (!aiGenerating) {
                                aiGenerating = true
                                onGenerateAi(description) { result ->
                                    aiGenerating = false
                                    result.onSuccess { draft -> pendingDraft = draft }
                                        .onFailure { onMessage("The planner isn't available right now. Try again later.") }
                                }
                            }
                        }
                    }
                }
                else -> item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Raised, contentColor = TextPrimary), border = BorderStroke(1.dp, Border), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("◉", color = Accent, fontSize = 34.sp)
                                Text(
                                    if (recording && recordingPeakAmplitude >= 300) "Voice detected — keep speaking."
                                    else if (recording) "Listening… speak clearly near the microphone."
                                    else "Record your tasks, play them back, then review the transcript before generating.",
                                    color = if (recordingPeakAmplitude >= 300) Success else TextMuted,
                                    textAlign = TextAlign.Center,
                                )
                                SmallOutlinedButton(if (recording) "Stop recording" else "Start recording", Modifier.fillMaxWidth().padding(top = 12.dp), {
                                    if (recording) {
                                        val durationMs = SystemClock.elapsedRealtime() - recordingStartedAt
                                        val stopped = runCatching { recorder?.stop() }.isSuccess
                                        recorder?.release()
                                        recorder = null
                                        recording = false
                                        val file = recordingFile
                                        val usable = stopped &&
                                            durationMs >= 1_000L &&
                                            file != null &&
                                            file.length() >= 4_096L
                                        if (usable) onMessage("Recording ready. Play it back before transcribing.")
                                        else {
                                            recordingFile?.delete()
                                            recordingFile = null
                                            onMessage("The recording was too short or could not be saved. Record again.")
                                        }
                                    } else if (
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        startRecording()
                                    } else {
                                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }, accent = recording)
                            }
                        }
                        if (!recording && recordingFile != null) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SmallOutlinedButton(
                                    if (playing) "Stop playback" else "Play recording",
                                    Modifier.weight(1f),
                                    {
                                        if (playing) {
                                            player?.stop()
                                            player?.release()
                                            player = null
                                            playing = false
                                        } else {
                                            val file = recordingFile ?: return@SmallOutlinedButton
                                            runCatching {
                                                MediaPlayer().also { activePlayer ->
                                                    activePlayer.setDataSource(file.absolutePath)
                                                    activePlayer.setOnCompletionListener {
                                                        it.release()
                                                        if (player === it) player = null
                                                        playing = false
                                                    }
                                                    activePlayer.prepare()
                                                    activePlayer.start()
                                                    player = activePlayer
                                                    playing = true
                                                }
                                            }.onFailure {
                                                player?.release()
                                                player = null
                                                playing = false
                                                onMessage("The recording couldn't be played. Please record it again.")
                                            }
                                        }
                                    },
                                )
                                SmallOutlinedButton(
                                    "Record again",
                                    Modifier.weight(1f),
                                    {
                                        player?.release()
                                        player = null
                                        playing = false
                                        recordingFile?.delete()
                                        recordingFile = null
                                        transcript = ""
                                        pendingDraft = null
                                    },
                                )
                            }
                            SmallOutlinedButton(
                                if (transcribing) "Transcribing…" else "Transcribe recording",
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                {
                                    val file = recordingFile ?: return@SmallOutlinedButton
                                    if (!transcribing) {
                                        transcribing = true
                                        scope.launch {
                                            val audio = runCatching { withContext(Dispatchers.IO) { file.readBytes() } }
                                            audio.fold(
                                                onSuccess = { bytes ->
                                                    onTranscribe(bytes, "audio/mp4") { result ->
                                                        transcribing = false
                                                        result.onSuccess { value -> transcript = value }
                                                            .onFailure { onMessage("Voice transcription isn't available right now. Try typing instead.") }
                                                    }
                                                },
                                                onFailure = {
                                                    transcribing = false
                                                    onMessage("The recording couldn't be read. Please record it again.")
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                        if (transcript.isNotBlank() && pendingDraft == null) {
                            LabeledTextField(
                                "Review transcript",
                                transcript,
                                {
                                    transcript = it
                                    pendingDraft = null
                                },
                                minLines = 4,
                            )
                            Spacer(Modifier.height(14.dp))
                            PrimaryButton("Generate plan from voice") {
                                if (!aiGenerating) {
                                    aiGenerating = true
                                    onGenerateAi(transcript) { result ->
                                        aiGenerating = false
                                        result.onSuccess { draft -> pendingDraft = draft }
                                            .onFailure { onMessage("The planner isn't available right now. Try again later.") }
                                    }
                                }
                            }
                        }
                        pendingDraft?.let { draft ->
                            PlanningDraftReview(
                                draft = draft,
                                onEditInput = { pendingDraft = null },
                                onShorten = {
                                    val total = draft.tasks.sumOf { it.minutes }.coerceAtLeast(1)
                                    pendingDraft = draft.copy(tasks = draft.tasks.map {
                                        it.copy(minutes = max(15, ((it.minutes * 180.0 / total) / 5).toInt() * 5))
                                    })
                                },
                                onSave = { onGenerated(pendingDraft ?: draft, "Voice Planner") },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanningDraftReview(
    draft: PlanningDraftBundle,
    onEditInput: () -> Unit,
    onShorten: () -> Unit,
    onSave: () -> Unit,
) {
    val totalMinutes = draft.tasks.sumOf { it.minutes }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Review draft before saving",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        draft.tasks.forEach { task ->
            Text(
                "• ${task.title} · ${task.minutes}m",
                Modifier.padding(top = 6.dp),
                color = TextMuted,
            )
        }
        if (totalMinutes > 180) {
            NoticeCard(
                "🛡 This draft is ${formatMinutes(totalMinutes)}, above your usual 3h capacity.",
                Amber,
            )
        }
        SmallOutlinedButton(
            "Edit input",
            Modifier.fillMaxWidth().padding(top = 10.dp),
            onEditInput,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (totalMinutes > 180) {
                SmallOutlinedButton("Shorten all", Modifier.weight(1f), onShorten)
            }
            SmallOutlinedButton(
                if (totalMinutes > 180) "Save anyway" else "Save plan",
                Modifier.weight(1f),
                onSave,
                accent = true,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchedulerSheet(
    workStart: String,
    breaks: List<ScheduleTask>,
    previousScheduleAvailable: Boolean,
    onDismiss: () -> Unit,
    onWorkStartChanged: (String) -> Unit,
    onRunScheduler: () -> Unit,
    onUndoSchedule: () -> Unit,
    onAddBreak: () -> Unit,
    onRemoveBreak: (ScheduleTask) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Card, contentColor = TextPrimary, dragHandle = null) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 14.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SheetHeader("SMART SCHEDULING", "Scheduler settings", onDismiss) }
            item {
                SettingsCard("Your day starts at") {
                    Text("The first flexible task will begin at this time. Fixed items and breaks stay protected.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    TimeField("Your day starts at", workStart, onWorkStartChanged)
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(schedulerActionLabel(workStart), onRunScheduler)
                    SmallOutlinedButton("Undo last scheduling", Modifier.fillMaxWidth().padding(top = 8.dp), onUndoSchedule)
                    if (!previousScheduleAvailable) Text("No scheduling operation to undo yet.", Modifier.padding(top = 6.dp), color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                SettingsCard("Protected breaks") {
                    if (breaks.isEmpty()) Text("No breaks added yet.", color = TextMuted)
                    breaks.forEach { task ->
                        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("☕ ${task.fixedTime ?: "Flexible"} · ${task.durationMinutes} min", Modifier.weight(1f))
                            TextButton(onClick = { onRemoveBreak(task) }) { Text("Remove", color = Danger) }
                        }
                    }
                    SmallOutlinedButton("☕ Add protected break", Modifier.fillMaxWidth().padding(top = 10.dp), onAddBreak)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageTaskSheet(
    task: ScheduleTask,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit,
    onFailed: (String) -> Unit,
    onDefer: () -> Unit,
    onDelete: () -> Unit,
) {
    var moveTime by remember { mutableStateOf(task.scheduledTime ?: task.fixedTime ?: "13:00") }
    val failureReasons = listOf(
        "Blocked / waiting on someone",
        "Missing info or resources",
        "Ran out of time",
        "Took longer than expected",
        "Higher priority came up",
        "Interrupted or meeting conflict",
        "Low energy or health",
        "Task unclear or too difficult",
        "No longer needed",
        "Other",
    )
    var failureReason by remember { mutableStateOf(failureReasons.first()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Card,
        contentColor = TextPrimary,
        dragHandle = { Box(Modifier.padding(vertical = 10.dp).size(38.dp, 4.dp).clip(CircleShape).background(Border)) },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            SheetHeader("TASK ACTIONS", task.title, onDismiss)
            Text("Change the time, record why it failed, or move it out of today.", Modifier.padding(top = 4.dp, bottom = 12.dp), color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            SettingsCard("Move task") {
                Text("Choose a new start time. Zen Maestro will protect fixed tasks from conflicts.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                TimeField("New start time", moveTime) { moveTime = it }
                Spacer(Modifier.height(14.dp))
                PrimaryButton("Move task") { onMove(moveTime) }
            }
            Spacer(Modifier.height(12.dp))
            SettingsCard("Task outcome") {
                OptionSelector("Failure reason", failureReason, failureReasons, { failureReason = it })
                SmallOutlinedButton("Mark as failed", Modifier.fillMaxWidth().padding(top = 8.dp), { onFailed(failureReason) })
            }
            SmallOutlinedButton("Move to tomorrow", Modifier.fillMaxWidth().padding(top = 12.dp), onDefer)
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth().height(50.dp).padding(top = 10.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Danger.copy(alpha = .45f)),
            ) { Text("Delete task", color = Danger, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun SheetHeader(eyebrow: String, title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Eyebrow(eyebrow); Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge) }
        TextButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Text("×", fontSize = 24.sp, color = TextMuted) }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Raised, contentColor = TextPrimary), border = BorderStroke(1.dp, Border), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(title, color = AccentText, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    suffix: String? = null,
    minLines: Int = 1,
) {
    Column(Modifier.fillMaxWidth().padding(top = 13.dp)) {
        Text(label, Modifier.padding(bottom = 7.dp), color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        OutlinedTextField(
            value, onValueChange, Modifier.fillMaxWidth(), placeholder = { Text(placeholder) },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            suffix = suffix?.let { { Text(it) } }, minLines = minLines,
            shape = RoundedCornerShape(11.dp),
        )
    }
}

@Composable
private fun OptionSelector(label: String, value: String, options: List<String>, onChanged: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.padding(top = 13.dp)) {
        Text(label, Modifier.padding(bottom = 7.dp), color = TextMuted, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Border),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Card, contentColor = TextPrimary),
            ) {
                Text(value, Modifier.weight(1f), textAlign = TextAlign.Start, style = MaterialTheme.typography.bodyMedium)
                Text(if (expanded) "⌃" else "⌄", color = TextMuted, fontSize = 16.sp)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Raised),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, color = if (option == value) Accent else TextPrimary, style = MaterialTheme.typography.bodyMedium) },
                        trailingIcon = { if (option == value) Text("✓", color = Accent, fontWeight = FontWeight.Bold) },
                        onClick = { onChanged(option); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick, modifier.height(48.dp), shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) AccentLine else Border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) AccentSoft else Card, contentColor = if (selected) AccentText else TextMuted),
    ) { Text(text, fontWeight = FontWeight.Bold) }
}

@Composable
private fun TimeField(label: String, value: String, onChanged: (String) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 13.dp)) {
        Text(label, Modifier.padding(bottom = 7.dp), color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        OutlinedButton(
            onClick = {
                TimePickerDialog(
                    context,
                    { _, hour, minute -> onChanged("%02d:%02d".format(hour, minute)) },
                    minutesOf(value) / 60,
                    minutesOf(value) % 60,
                    false,
                ).show()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(11.dp),
            border = BorderStroke(1.dp, Border), colors = ButtonDefaults.outlinedButtonColors(containerColor = Card),
        ) { Text(displayTime(value), Modifier.weight(1f), textAlign = TextAlign.Start); Text("◷", color = Accent) }
    }
}

internal fun displayTime(value: String): String {
    val minutes = minutesOf(value)
    val hour = minutes / 60
    return "${if (hour % 12 == 0) 12 else hour % 12}:${"%02d".format(minutes % 60)} ${if (hour >= 12) "PM" else "AM"}"
}

internal fun schedulerActionLabel(workStart: String): String =
    "✦ Build schedule from ${displayTime(workStart)}"

internal fun dayStartLabel(workStart: String): String = "Day starts at ${displayTime(workStart)}"

@Composable
private fun AppNavigationRail(onHome: () -> Unit, onCoach: () -> Unit, onMore: () -> Unit) {
    val entries = listOf("⌂" to "Home", "◷" to "Schedule", "◇" to "Coach", "•••" to "More")
    NavigationRail(Modifier.fillMaxHeight(), containerColor = NavigationSurface) {
        Spacer(Modifier.height(84.dp))
        entries.forEach { (icon, label) ->
            NavigationRailItem(
                selected = label == "Schedule", onClick = {
                    if (label == "Home") onHome()
                    else if (label == "Coach") onCoach()
                    else if (label == "More") onMore()
                },
                icon = { Text(icon, fontSize = 24.sp, fontWeight = FontWeight.Bold) },
                label = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = Accent,
                    selectedTextColor = AccentText,
                    indicatorColor = AccentSoft,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                ),
            )
        }
    }
}

@Composable
private fun Eyebrow(text: String) = Text(text, color = TextMuted, style = MaterialTheme.typography.labelSmall)

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF1B110D))) {
        Text(text, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun SmallOutlinedButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit, accent: Boolean = false) {
    OutlinedButton(
        onClick, modifier.height(48.dp), shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, if (accent) Accent else Border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (accent) Accent else Raised, contentColor = if (accent) Color(0xFF1B110D) else TextPrimary),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun AccentOutlinedButton(text: String, onClick: () -> Unit) = SmallOutlinedButton(text, onClick = onClick, accent = true)

private fun formatClock(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)
private fun formatMinutes(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

@Suppress("DEPRECATION")
private fun createAudioRecorder(context: Context, file: File): MediaRecorder {
    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        MediaRecorder()
    }
    return recorder.apply {
        setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioSamplingRate(44_100)
        setAudioEncodingBitRate(128_000)
        setOutputFile(file.absolutePath)
        prepare()
    }
}

private fun minutesOf(time: String): Int {
    val parts = time.split(":")
    return (parts.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (parts.getOrNull(1)?.toIntOrNull() ?: 0)
}

private fun timeOf(minutes: Int): String = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)

private fun overlaps(firstStart: String, firstDuration: Int, secondStart: String, secondDuration: Int): Boolean {
    val a = minutesOf(firstStart); val b = minutesOf(secondStart)
    return a < b + secondDuration && b < a + firstDuration
}

private fun scheduleTasks(tasks: MutableList<ScheduleTask>, workStart: String) {
    var cursor = minutesOf(workStart)
    tasks.indices.forEach { index ->
        val task = tasks[index]
        val start = task.fixedTime ?: timeOf(cursor)
        tasks[index] = task.copy(scheduledTime = start)
        if (task.fixedTime == null) cursor += task.durationMinutes
    }
}
