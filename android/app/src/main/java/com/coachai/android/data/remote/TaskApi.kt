package com.coachai.android.data.remote

import com.coachai.android.BuildConfig
import com.coachai.android.domain.HomeTask
import com.coachai.android.domain.TaskStatus
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class PlanningDraftItem(
    val title: String,
    val description: String,
    val minutes: Int,
    val priority: Int,
    val category: String,
    val isNewCategory: Boolean,
    val suggestedCategoryColor: String?,
    val fixedStart: String?,
    val needsReview: Boolean,
    val reviewReason: String?,
)

data class PlanningDraftBundle(
    val rawInput: String,
    val planningNotes: String,
    val tasks: List<PlanningDraftItem>,
)

data class PlanningApplyResult(
    val planId: Long,
    val scheduleRevision: Int,
    val taskIds: List<Long>,
    val taskBreaks: List<Boolean>,
)

data class TodayPlanTarget(
    val planId: Long,
    val scheduleRevision: Int,
)

data class PlanDayResult(
    val date: LocalDate,
    val planId: Long?,
    val scheduleRevision: Int,
    val tasks: List<HomeTask>,
)

data class ScheduleApplyResult(
    val scheduleRevision: Int,
    val operationId: String?,
    val scheduledStarts: Map<Long, String>,
)

data class ScheduleMoveResult(
    val scheduleRevision: Int,
    val operationId: String?,
)

sealed interface TaskRemoteResult<out T> {
    data class Success<T>(val value: T) : TaskRemoteResult<T>
    data object Unauthorized : TaskRemoteResult<Nothing>
    data object Forbidden : TaskRemoteResult<Nothing>
    data object Conflict : TaskRemoteResult<Nothing>
    data object NetworkError : TaskRemoteResult<Nothing>
    data object InvalidResponse : TaskRemoteResult<Nothing>
}

interface TaskRemoteDataSource {
    suspend fun ensureTodayPlan(token: String, key: String): TaskRemoteResult<TodayPlanTarget>
    suspend fun ensurePlanDate(token: String, date: LocalDate, key: String): TaskRemoteResult<TodayPlanTarget>
    suspend fun loadPlanDay(token: String, date: LocalDate): TaskRemoteResult<PlanDayResult>
    suspend fun schedule(token: String, planId: Long, expectedRevision: Int, workStart: String, key: String): TaskRemoteResult<ScheduleApplyResult>
    suspend fun move(token: String, taskId: Long, expectedRevision: Int, newStart: String, key: String): TaskRemoteResult<ScheduleMoveResult>
    suspend fun undoSchedule(token: String, operationId: String, key: String): TaskRemoteResult<Int>
    suspend fun draft(token: String, input: String): TaskRemoteResult<PlanningDraftBundle>
    suspend fun applyDraft(token: String, draft: PlanningDraftBundle, key: String): TaskRemoteResult<PlanningApplyResult>
    suspend fun create(token: String, planId: Long, task: HomeTask, description: String, isBreak: Boolean, key: String): TaskRemoteResult<HomeTask>
    suspend fun command(token: String, task: HomeTask, command: String, failureReason: String?, key: String): TaskRemoteResult<HomeTask>
    suspend fun delete(token: String, task: HomeTask, key: String): TaskRemoteResult<Unit>
    suspend fun transcribe(token: String, audio: ByteArray, mimeType: String): TaskRemoteResult<String>
}

class TaskApi(
    private val baseUrl: String,
    private val allowDebugCleartext: Boolean = BuildConfig.DEBUG,
) : TaskRemoteDataSource {
    override suspend fun ensureTodayPlan(
        token: String,
        key: String,
    ): TaskRemoteResult<TodayPlanTarget> =
        request("POST", "/v1/plans/today", token, key).map { payload ->
            val item = JSONObject(payload)
            TodayPlanTarget(
                planId = item.getLong("plan_id").also { require(it > 0) },
                scheduleRevision = item.getInt("schedule_revision").also { require(it >= 0) },
            )
        }

    override suspend fun ensurePlanDate(
        token: String,
        date: LocalDate,
        key: String,
    ): TaskRemoteResult<TodayPlanTarget> =
        request("POST", "/v1/plans/by-date/$date", token, key).map(::parsePlanTarget)

    override suspend fun loadPlanDay(
        token: String,
        date: LocalDate,
    ): TaskRemoteResult<PlanDayResult> =
        request("GET", "/v1/plans/by-date/$date", token, "plan-day-$date").map { payload ->
            val root = JSONObject(payload)
            val returnedDate = LocalDate.parse(root.getString("date"))
            require(returnedDate == date)
            if (root.isNull("plan")) {
                PlanDayResult(returnedDate, null, 0, emptyList())
            } else {
                val plan = root.getJSONObject("plan")
                val items = plan.getJSONArray("tasks")
                require(items.length() <= 100)
                PlanDayResult(
                    date = returnedDate,
                    planId = plan.getLong("plan_id").also { require(it > 0) },
                    scheduleRevision = plan.getInt("schedule_revision").also { require(it >= 0) },
                    tasks = List(items.length()) { index -> parseTask(items.getJSONObject(index).toString()) },
                )
            }
        }

    private fun parsePlanTarget(payload: String): TodayPlanTarget {
        val item = JSONObject(payload)
        return TodayPlanTarget(
            planId = item.getLong("plan_id").also { require(it > 0) },
            scheduleRevision = item.getInt("schedule_revision").also { require(it >= 0) },
        )
    }

    override suspend fun schedule(
        token: String,
        planId: Long,
        expectedRevision: Int,
        workStart: String,
        key: String,
    ): TaskRemoteResult<ScheduleApplyResult> {
        require(planId > 0 && expectedRevision >= 0 && workStart.matches(HH_MM))
        val body = JSONObject()
            .put("plan_id", planId)
            .put("expected_revision", expectedRevision)
            .put("work_day_start", workStart)
            .toString()
        return request("POST", "/v1/scheduling/apply", token, key, body).map { payload ->
            val root = JSONObject(payload)
            val starts = mutableMapOf<Long, String>()
            val items = root.getJSONArray("scheduled_tasks")
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                if (!item.isNull("task_id") && !item.isNull("scheduled_start")) {
                    starts[item.getLong("task_id")] = item.getString("scheduled_start").take(5)
                }
            }
            ScheduleApplyResult(
                scheduleRevision = root.getInt("schedule_revision").also { require(it >= 0) },
                operationId = root.optString("operation_id")
                    .takeUnless { root.isNull("operation_id") || it.isBlank() },
                scheduledStarts = starts,
            )
        }
    }

    override suspend fun move(
        token: String,
        taskId: Long,
        expectedRevision: Int,
        newStart: String,
        key: String,
    ): TaskRemoteResult<ScheduleMoveResult> {
        require(taskId > 0 && expectedRevision >= 0 && newStart.matches(HH_MM))
        val body = JSONObject()
            .put("expected_revision", expectedRevision)
            .put("new_start", newStart)
            .toString()
        return request("POST", "/v1/scheduling/tasks/$taskId/move", token, key, body).map { payload ->
            val root = JSONObject(payload)
            ScheduleMoveResult(
                scheduleRevision = root.getInt("schedule_revision").also { require(it >= 0) },
                operationId = root.optString("operation_id")
                    .takeUnless { root.isNull("operation_id") || it.isBlank() },
            )
        }
    }

    override suspend fun undoSchedule(
        token: String,
        operationId: String,
        key: String,
    ): TaskRemoteResult<Int> {
        require(operationId.matches(OPERATION_ID))
        return request(
            "POST",
            "/v1/scheduling/operations/$operationId/undo",
            token,
            key,
        ).map { payload ->
            val revisions = JSONObject(payload).getJSONObject("schedule_revisions")
            require(revisions.length() == 1)
            revisions.getInt(revisions.keys().next()).also { require(it >= 0) }
        }
    }

    override suspend fun draft(token: String, input: String): TaskRemoteResult<PlanningDraftBundle> {
        val clean = input.trim()
        if (clean.isEmpty() || clean.length > 10_000) return TaskRemoteResult.InvalidResponse
        return request(
            "POST", "/v1/planning/draft", token, "draft-${java.util.UUID.randomUUID()}",
            JSONObject().put("raw_input", clean).toString(),
        ).map { payload ->
            val root = JSONObject(payload)
            val tasks = root.getJSONArray("tasks")
            require(tasks.length() in 1..100)
            val items = List(tasks.length()) { index ->
                val item = tasks.getJSONObject(index)
                PlanningDraftItem(
                    title = item.getString("title").also { require(it.isNotBlank()) },
                    description = item.optString("description").take(4_000),
                    minutes = item.getInt("estimated_minutes").also { require(it in 1..960) },
                    priority = item.getInt("priority").also { require(it in 1..5) },
                    category = item.getString("category_name"),
                    isNewCategory = item.optBoolean("is_new_category"),
                    suggestedCategoryColor = item.optString("suggested_category_color")
                        .takeUnless { item.isNull("suggested_category_color") || it.isBlank() },
                    fixedStart = item.optString("fixed_start").takeUnless { item.isNull("fixed_start") || it.isBlank() },
                    needsReview = item.optBoolean("needs_review"),
                    reviewReason = item.optString("review_reason")
                        .takeUnless { item.isNull("review_reason") || it.isBlank() },
                )
            }
            PlanningDraftBundle(
                rawInput = clean,
                planningNotes = root.getString("planning_notes").also { require(it.isNotBlank()) },
                tasks = items,
            )
        }
    }

    override suspend fun applyDraft(
        token: String,
        draft: PlanningDraftBundle,
        key: String,
    ): TaskRemoteResult<PlanningApplyResult> {
        require(draft.rawInput.isNotBlank() && draft.tasks.isNotEmpty())
        val draftJson = JSONObject().put("planning_notes", draft.planningNotes)
        val taskJson = org.json.JSONArray()
        draft.tasks.forEach { task ->
            taskJson.put(JSONObject().apply {
                put("title", task.title)
                put("description", task.description)
                put("category_name", task.category)
                put("is_new_category", task.isNewCategory)
                put("suggested_category_color", task.suggestedCategoryColor ?: JSONObject.NULL)
                put("priority", task.priority)
                put("estimated_minutes", task.minutes)
                put("is_fixed_time", task.fixedStart != null)
                put("fixed_start", task.fixedStart ?: JSONObject.NULL)
                put("needs_review", task.needsReview)
                put("review_reason", task.reviewReason ?: JSONObject.NULL)
            })
        }
        draftJson.put("tasks", taskJson)
        val body = JSONObject()
            .put("raw_input", draft.rawInput)
            .put("draft", draftJson)
            .toString()
        return request("POST", "/v1/planning/apply", token, key, body).map { payload ->
            val root = JSONObject(payload)
            val saved = root.getJSONArray("tasks")
            PlanningApplyResult(
                planId = root.getLong("plan_id").also { require(it > 0) },
                scheduleRevision = root.getInt("schedule_revision").also { require(it >= 0) },
                taskIds = List(saved.length()) { index ->
                    saved.getJSONObject(index).getLong("task_id").also { require(it > 0) }
                },
                taskBreaks = List(saved.length()) { index ->
                    saved.getJSONObject(index).getBoolean("is_break")
                },
            ).also { require(it.taskIds.size == draft.tasks.size) }
        }
    }
    override suspend fun create(
        token: String, planId: Long, task: HomeTask, description: String, isBreak: Boolean, key: String,
    ): TaskRemoteResult<HomeTask> {
        val body = JSONObject().apply {
            put("plan_id", planId); put("title", task.title); put("description", description)
            put("estimated_minutes", task.estimatedMinutes); put("priority", task.priority)
            put("is_fixed_time", task.isFixedTime); put("is_break", isBreak)
            if (task.isFixedTime) {
                val start = task.scheduledStart ?: error("A fixed task requires a start time")
                val startMinutes = parseClockMinutes(start)
                val endMinutes = startMinutes + task.estimatedMinutes
                require(endMinutes <= 24 * 60)
                put("scheduled_start", clockText(startMinutes))
                put("scheduled_end", clockText(endMinutes))
            }
        }.toString()
        return request("POST", "/v1/tasks", token, key, body).map(::parseTask)
    }

    override suspend fun command(
        token: String, task: HomeTask, command: String, failureReason: String?, key: String,
    ): TaskRemoteResult<HomeTask> {
        require(command in setOf("start", "pause", "resume", "complete", "fail", "defer"))
        val body = JSONObject().put("expected_version", task.version)
        failureReason?.let { body.put("failure_reason", it) }
        return request("POST", "/v1/tasks/${task.id}/$command", token, key, body.toString()).map(::parseTask)
    }

    override suspend fun delete(token: String, task: HomeTask, key: String): TaskRemoteResult<Unit> =
        request("DELETE", "/v1/tasks/${task.id}?expected_version=${task.version}", token, key).withoutPayload()

    override suspend fun transcribe(
        token: String,
        audio: ByteArray,
        mimeType: String,
    ): TaskRemoteResult<String> = withContext(Dispatchers.IO) {
        try {
            require(token.isNotBlank())
            require(audio.isNotEmpty() && audio.size <= 15 * 1024 * 1024)
            require(mimeType in setOf("audio/mp4", "audio/m4a", "audio/aac", "audio/wav", "audio/mpeg"))
            val connection = endpoint("/v1/coach/voice/transcribe").toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 90_000
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(audio.size)
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", mimeType)
                connection.outputStream.use { it.write(audio) }
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> TaskRemoteResult.Success(
                        JSONObject(connection.inputStream.readBounded())
                            .getString("transcript")
                            .trim()
                            .also { require(it.isNotEmpty() && it.length <= 20_000) },
                    )
                    HttpURLConnection.HTTP_UNAUTHORIZED -> TaskRemoteResult.Unauthorized
                    HttpURLConnection.HTTP_FORBIDDEN -> TaskRemoteResult.Forbidden
                    in 500..599 -> TaskRemoteResult.NetworkError
                    else -> TaskRemoteResult.InvalidResponse
                }
            } finally {
                connection.disconnect()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            TaskRemoteResult.NetworkError
        } catch (_: Exception) {
            TaskRemoteResult.InvalidResponse
        }
    }

    private suspend fun request(
        method: String, path: String, token: String, key: String, body: String? = null,
    ): TaskRemoteResult<String> = withContext(Dispatchers.IO) {
        try {
            require(token.isNotBlank() && key.isNotBlank() && key.length <= 200)
            val bytes = body?.toByteArray(Charsets.UTF_8)
            require(bytes == null || bytes.size <= 64 * 1_024)
            val connection = endpoint(path).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method; connection.connectTimeout = 10_000; connection.readTimeout = 25_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Idempotency-Key", key)
                if (bytes != null) { connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json"); connection.outputStream.use { it.write(bytes) } }
                when (connection.responseCode) {
                    in 200..299 -> TaskRemoteResult.Success(connection.inputStream.readBounded())
                    HttpURLConnection.HTTP_UNAUTHORIZED -> TaskRemoteResult.Unauthorized
                    HttpURLConnection.HTTP_FORBIDDEN -> TaskRemoteResult.Forbidden
                    HttpURLConnection.HTTP_CONFLICT -> TaskRemoteResult.Conflict
                    in 500..599 -> TaskRemoteResult.NetworkError
                    else -> TaskRemoteResult.InvalidResponse
                }
            } finally { connection.disconnect() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { TaskRemoteResult.NetworkError }
        catch (_: Exception) { TaskRemoteResult.InvalidResponse }
    }

    private fun endpoint(path: String): URI {
        val base = URI.create(baseUrl.trim().trimEnd('/'))
        require(base.host?.isNotBlank() == true && base.userInfo == null && base.query == null && base.fragment == null)
        val secure = base.scheme.equals("https", true); val local = base.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        require(secure || (allowDebugCleartext && local && base.scheme == "http"))
        return URI.create("${base.toASCIIString()}$path")
    }

    private fun parseClockMinutes(value: String): Int {
        val clean = value.take(5)
        require(clean.matches(HH_MM))
        return clean.substringBefore(':').toInt() * 60 + clean.substringAfter(':').toInt()
    }

    private fun clockText(minutes: Int): String =
        "%02d:%02d".format(minutes / 60, minutes % 60)

    private fun parseTask(payload: String): HomeTask {
        val item = JSONObject(payload)
        return HomeTask(
            id = item.getLong("task_id"), title = item.getString("title"),
            scheduledStart = item.optString("scheduled_start").takeUnless { item.isNull("scheduled_start") || it.isBlank() },
            estimatedMinutes = item.getInt("estimated_minutes"), priority = item.getInt("priority"),
            status = when (item.getString("status")) { "pending" -> TaskStatus.PENDING; "in_progress" -> TaskStatus.IN_PROGRESS; "completed" -> TaskStatus.COMPLETED; "failed" -> TaskStatus.FAILED; else -> error("status") },
            isFixedTime = item.getBoolean("is_fixed_time"), planId = item.getLong("plan_id"),
            version = item.getInt("version"), isBreak = item.getBoolean("is_break"),
        )
    }

    private fun <T, R> TaskRemoteResult<T>.map(transform: (T) -> R): TaskRemoteResult<R> = when (this) {
        is TaskRemoteResult.Success -> runCatching { TaskRemoteResult.Success(transform(value)) }.getOrDefault(TaskRemoteResult.InvalidResponse)
        TaskRemoteResult.Unauthorized -> TaskRemoteResult.Unauthorized; TaskRemoteResult.Forbidden -> TaskRemoteResult.Forbidden
        TaskRemoteResult.Conflict -> TaskRemoteResult.Conflict
        TaskRemoteResult.NetworkError -> TaskRemoteResult.NetworkError; TaskRemoteResult.InvalidResponse -> TaskRemoteResult.InvalidResponse
    }
    private fun TaskRemoteResult<String>.withoutPayload(): TaskRemoteResult<Unit> = when (this) {
        is TaskRemoteResult.Success -> TaskRemoteResult.Success(Unit); TaskRemoteResult.Unauthorized -> TaskRemoteResult.Unauthorized
        TaskRemoteResult.Forbidden -> TaskRemoteResult.Forbidden
        TaskRemoteResult.Conflict -> TaskRemoteResult.Conflict; TaskRemoteResult.NetworkError -> TaskRemoteResult.NetworkError
        TaskRemoteResult.InvalidResponse -> TaskRemoteResult.InvalidResponse
    }
    private fun java.io.InputStream.readBounded(): String = use { input ->
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8_192); var total = 0
        while (true) { val count = input.read(buffer); if (count < 0) break; total += count; if (total > 256 * 1_024) throw IOException(); output.write(buffer, 0, count) }
        output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        val HH_MM = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")
        val OPERATION_ID = Regex("^[A-Za-z0-9_-]{1,200}$")
    }
}
