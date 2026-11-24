package com.coachai.android.data.remote

import com.coachai.android.BuildConfig
import com.coachai.android.domain.HomeTask
import com.coachai.android.domain.ProgressSummary
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

data class RemoteTodayPlan(
    val tasks: List<HomeTask>,
    val progress: ProgressSummary,
    val planId: Long? = null,
)

data class RemoteHomeSnapshot(
    val date: LocalDate,
    val displayName: String?,
    val plan: RemoteTodayPlan?,
)

sealed interface HomeRemoteResult {
    data class Success(val snapshot: RemoteHomeSnapshot) : HomeRemoteResult
    data object Unauthorized : HomeRemoteResult
    data object Forbidden : HomeRemoteResult
    data object NetworkError : HomeRemoteResult
    data object InvalidResponse : HomeRemoteResult
}

fun interface HomeRemoteDataSource {
    suspend fun loadToday(idToken: String): HomeRemoteResult
}

class HomeApi(
    private val baseUrl: String,
    private val allowDebugCleartext: Boolean = BuildConfig.DEBUG,
) : HomeRemoteDataSource {
    override suspend fun loadToday(idToken: String): HomeRemoteResult =
        withContext(Dispatchers.IO) {
            try {
                val endpoint = endpointUri()
                val connection = endpoint.toURL().openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Authorization", "Bearer $idToken")
                    when (connection.responseCode) {
                        HttpURLConnection.HTTP_OK -> HomeRemoteResult.Success(
                            parseSnapshot(connection.inputStream.readBounded()),
                        )
                        HttpURLConnection.HTTP_UNAUTHORIZED -> HomeRemoteResult.Unauthorized
                        HttpURLConnection.HTTP_FORBIDDEN -> HomeRemoteResult.Forbidden
                        in 500..599 -> HomeRemoteResult.NetworkError
                        else -> HomeRemoteResult.InvalidResponse
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                HomeRemoteResult.NetworkError
            } catch (_: Exception) {
                HomeRemoteResult.InvalidResponse
            }
        }

    private fun endpointUri(): URI {
        val base = URI.create(baseUrl.trim().trimEnd('/'))
        require(base.host?.isNotBlank() == true)
        require(base.userInfo == null && base.query == null && base.fragment == null)
        val secure = base.scheme.equals("https", ignoreCase = true)
        val localDebugHost = base.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        require(secure || (allowDebugCleartext && localDebugHost && base.scheme == "http"))
        return URI.create("${base.toASCIIString()}/v1/home")
    }

    private fun parseSnapshot(payload: String): RemoteHomeSnapshot {
        val root = JSONObject(payload)
        val localDate = LocalDate.parse(root.getString("date"))
        val displayName = root.optString("display_name")
            .takeUnless { root.isNull("display_name") || it.isBlank() }
        if (root.isNull("plan")) {
            return RemoteHomeSnapshot(localDate, displayName, null)
        }

        val plan = root.getJSONObject("plan")
        require(LocalDate.parse(plan.getString("plan_date")) == localDate)
        val progressJson = plan.getJSONObject("progress")
        val progress = ProgressSummary(
            completed = progressJson.nonNegativeInt("completed"),
            inProgress = progressJson.nonNegativeInt("in_progress"),
            pending = progressJson.nonNegativeInt("pending"),
            failed = progressJson.nonNegativeInt("failed"),
            total = progressJson.nonNegativeInt("total"),
        )
        require(
            progress.completed + progress.inProgress + progress.pending + progress.failed ==
                progress.total,
        )

        val items = plan.getJSONArray("tasks")
        require(items.length() <= MAX_HOME_TASKS)
        val tasks = buildList {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val title = item.getString("title").trim()
                val estimatedMinutes = item.getInt("estimated_minutes")
                val priority = item.getInt("priority")
                require(title.isNotEmpty() && title.length <= 200)
                require(estimatedMinutes in 1..960)
                require(priority in 1..5)
                add(
                    HomeTask(
                        id = item.getLong("task_id").also { require(it > 0) },
                        title = title,
                        scheduledStart = item.optString("scheduled_start")
                            .takeUnless { item.isNull("scheduled_start") || it.isBlank() },
                        estimatedMinutes = estimatedMinutes,
                        priority = priority,
                        status = item.getString("status").toTaskStatus(),
                        isFixedTime = item.getBoolean("is_fixed_time"),
                        planId = item.getLong("plan_id"),
                        version = item.getInt("version"),
                        isBreak = item.getBoolean("is_break"),
                    ),
                )
            }
        }
        val truncated = plan.getBoolean("tasks_truncated")
        require(if (truncated) progress.total > tasks.size else progress.total == tasks.size)
        return RemoteHomeSnapshot(
            date = localDate,
            displayName = displayName,
            plan = RemoteTodayPlan(tasks = tasks, progress = progress, planId = plan.getLong("plan_id")),
        )
    }

    private fun JSONObject.nonNegativeInt(name: String): Int =
        getInt(name).also { require(it >= 0) }

    private fun String.toTaskStatus(): TaskStatus = when (this) {
        "pending" -> TaskStatus.PENDING
        "in_progress" -> TaskStatus.IN_PROGRESS
        "completed" -> TaskStatus.COMPLETED
        "failed" -> TaskStatus.FAILED
        else -> error("Unsupported task status")
    }

    private fun java.io.InputStream.readBounded(): String = use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw IOException("Home response is too large")
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_HOME_TASKS = 100
        const val MAX_RESPONSE_BYTES = 1_048_576
    }
}
