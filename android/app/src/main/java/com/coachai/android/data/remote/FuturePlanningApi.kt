package com.coachai.android.data.remote

import com.coachai.android.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class FutureCandidate(
    val taskId: Long,
    val planDate: LocalDate,
    val title: String,
    val description: String,
    val remainingMinutes: Int,
    val priority: Int,
    val earliestStart: String?,
    val deadline: String?,
)

data class FutureTaskInput(
    val taskId: Long? = null,
    val temporaryId: String? = null,
    val title: String,
    val description: String = "",
    val remainingMinutes: Int,
    val priority: Int,
    val earliestStart: String? = null,
    val deadline: String? = null,
)

data class FuturePlanningRequest(
    val specificDay: Boolean,
    val planningStart: LocalDate,
    val planningEnd: LocalDate,
    val workDayStart: String,
    val tasks: List<FutureTaskInput>,
)

data class FutureSegmentPreview(
    val date: LocalDate,
    val start: String,
    val end: String,
)

data class FutureOutcomePreview(
    val title: String,
    val placed: Boolean,
    val reason: String,
    val message: String,
    val remainingMinutes: Int,
    val segments: List<FutureSegmentPreview>,
)

data class FuturePreview(
    val placedCount: Int,
    val unplacedCount: Int,
    val outcomes: List<FutureOutcomePreview>,
)

data class FutureApplyResult(val operationId: String)

interface FuturePlanningRemoteDataSource {
    suspend fun candidates(token: String, start: LocalDate, end: LocalDate): TaskRemoteResult<List<FutureCandidate>>
    suspend fun preview(token: String, request: FuturePlanningRequest): TaskRemoteResult<FuturePreview>
    suspend fun apply(token: String, request: FuturePlanningRequest, key: String): TaskRemoteResult<FutureApplyResult>
    suspend fun undo(token: String, operationId: String, key: String): TaskRemoteResult<Unit>
}

class FuturePlanningApi(
    private val baseUrl: String,
    private val allowDebugCleartext: Boolean = BuildConfig.DEBUG,
) : FuturePlanningRemoteDataSource {
    override suspend fun candidates(
        token: String, start: LocalDate, end: LocalDate,
    ): TaskRemoteResult<List<FutureCandidate>> {
        val query = "planning_start=${encode(start.toString())}&planning_end=${encode(end.toString())}"
        return request("GET", "/v1/future-planning/candidates?$query", token).map { payload ->
            val items = JSONObject(payload).getJSONArray("tasks")
            require(items.length() <= 500)
            List(items.length()) { index ->
                val item = items.getJSONObject(index)
                FutureCandidate(
                    taskId = item.getLong("task_id").also { require(it > 0) },
                    planDate = LocalDate.parse(item.getString("plan_date")),
                    title = item.getString("title").also { require(it.isNotBlank()) },
                    description = item.optString("description"),
                    remainingMinutes = item.getInt("remaining_minutes").also { require(it >= 0) },
                    priority = item.getInt("priority").also { require(it in 1..5) },
                    earliestStart = item.optionalString("earliest_start"),
                    deadline = item.optionalString("deadline"),
                )
            }
        }
    }

    override suspend fun preview(
        token: String, request: FuturePlanningRequest,
    ): TaskRemoteResult<FuturePreview> = request(
        "POST", "/v1/future-planning/preview", token, body = request.toJson().toString(),
    ).map(::parsePreview)

    override suspend fun apply(
        token: String, request: FuturePlanningRequest, key: String,
    ): TaskRemoteResult<FutureApplyResult> = request(
        "POST", "/v1/future-planning/apply", token, key, request.toJson().toString(),
    ).map { payload ->
        val operationId = JSONObject(payload).getString("operation_id").trim()
        require(operationId.matches(Regex("^[A-Za-z0-9_-]{1,200}$")))
        FutureApplyResult(operationId)
    }

    override suspend fun undo(
        token: String, operationId: String, key: String,
    ): TaskRemoteResult<Unit> {
        require(operationId.matches(Regex("^[A-Za-z0-9_-]{1,200}$")))
        return request(
            "POST", "/v1/scheduling/operations/$operationId/undo", token, key,
        ).map { }
    }

    private fun FuturePlanningRequest.toJson(): JSONObject = JSONObject().apply {
        require(planningStart <= planningEnd)
        require(tasks.isNotEmpty() && tasks.size <= 50)
        put("scope", if (specificDay) "specific_day" else "date_range")
        put("input_method", "manual")
        put("planning_start", planningStart.toString())
        put("planning_end", planningEnd.toString())
        put("work_day_start", workDayStart)
        put("tasks", JSONArray().apply {
            tasks.forEach { task ->
                require(task.title.isNotBlank() && task.remainingMinutes in 1..10080 && task.priority in 1..5)
                put(JSONObject().apply {
                    task.taskId?.let { put("task_id", it) }
                    if (task.taskId == null) {
                        put("temporary_id", task.temporaryId ?: "draft:${UUID.randomUUID()}")
                        put("duration_source", "user_explicit")
                    }
                    put("title", task.title.trim())
                    put("description", task.description.trim())
                    put("remaining_minutes", task.remainingMinutes)
                    put("priority", task.priority)
                    task.earliestStart?.let { put("earliest_start", it) }
                    task.deadline?.let { put("deadline", it) }
                })
            }
        })
    }

    private fun parsePreview(payload: String): FuturePreview {
        val root = JSONObject(payload)
        val items = root.getJSONArray("outcomes")
        require(items.length() <= 50)
        return FuturePreview(
            placedCount = root.getInt("placed_count"),
            unplacedCount = root.getInt("unplaced_count"),
            outcomes = List(items.length()) { index ->
                val item = items.getJSONObject(index)
                val segments = item.getJSONArray("segments")
                FutureOutcomePreview(
                    title = item.getString("title"),
                    placed = item.getBoolean("placed"),
                    reason = item.getString("reason"),
                    message = item.getString("message"),
                    remainingMinutes = item.getInt("remaining_minutes"),
                    segments = List(segments.length()) { segmentIndex ->
                        val segment = segments.getJSONObject(segmentIndex)
                        FutureSegmentPreview(
                            date = LocalDate.parse(segment.getString("scheduled_date")),
                            start = segment.getString("scheduled_start").take(5),
                            end = segment.getString("scheduled_end").take(5),
                        )
                    },
                )
            },
        )
    }

    private suspend fun request(
        method: String,
        path: String,
        token: String,
        key: String? = null,
        body: String? = null,
    ): TaskRemoteResult<String> = withContext(Dispatchers.IO) {
        try {
            require(token.isNotBlank())
            val bytes = body?.toByteArray(Charsets.UTF_8)
            require(bytes == null || bytes.size <= 128 * 1024)
            val connection = endpoint(path).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 45_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")
                key?.let { connection.setRequestProperty("Idempotency-Key", it) }
                if (bytes != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(bytes) }
                }
                when (connection.responseCode) {
                    in 200..299 -> TaskRemoteResult.Success(connection.inputStream.readBounded())
                    HttpURLConnection.HTTP_UNAUTHORIZED -> TaskRemoteResult.Unauthorized
                    HttpURLConnection.HTTP_FORBIDDEN -> TaskRemoteResult.Forbidden
                    HttpURLConnection.HTTP_CONFLICT -> TaskRemoteResult.Conflict
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

    private fun endpoint(path: String): URI {
        val base = URI.create(baseUrl.trim().trimEnd('/'))
        require(base.host?.isNotBlank() == true && base.userInfo == null && base.query == null && base.fragment == null)
        val local = base.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        require(base.scheme.equals("https", true) || (allowDebugCleartext && local && base.scheme == "http"))
        return URI.create("${base.toASCIIString()}$path")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun JSONObject.optionalString(name: String): String? =
        optString(name).takeUnless { isNull(name) || it.isBlank() }
    private fun java.io.InputStream.readBounded(): String = use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > 512 * 1024) throw IOException()
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }

    private fun <T, R> TaskRemoteResult<T>.map(transform: (T) -> R): TaskRemoteResult<R> = when (this) {
        is TaskRemoteResult.Success -> runCatching { TaskRemoteResult.Success(transform(value)) }
            .getOrDefault(TaskRemoteResult.InvalidResponse)
        TaskRemoteResult.Unauthorized -> TaskRemoteResult.Unauthorized
        TaskRemoteResult.Forbidden -> TaskRemoteResult.Forbidden
        TaskRemoteResult.Conflict -> TaskRemoteResult.Conflict
        TaskRemoteResult.NetworkError -> TaskRemoteResult.NetworkError
        TaskRemoteResult.InvalidResponse -> TaskRemoteResult.InvalidResponse
    }
}
