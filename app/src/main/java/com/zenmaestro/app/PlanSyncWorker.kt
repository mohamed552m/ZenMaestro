package com.zenmaestro.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class PlanSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val userUid = inputData.getString(KEY_USER_UID).orEmpty()
        val baseUrl = BuildConfig.API_BASE_URL.trimEnd('/')
        if (userUid.isBlank() || baseUrl.isBlank()) return Result.success()
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null || user.uid != userUid) return Result.success()

        return try {
            val store = PlanStore(applicationContext, scheduleInitialSync = false)
            enqueueLegacyLocalTasksIfNeeded(userUid, store)
            var tokens = BackendAuthTokenProvider.get(user, forceRefresh = false)
            for (operation in PlanSyncQueue.load(applicationContext, userUid)) {
                var responseCode = sendOperation(baseUrl, tokens, operation)
                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    tokens = BackendAuthTokenProvider.get(user, forceRefresh = true)
                    responseCode = sendOperation(baseUrl, tokens, operation)
                }
                val accepted = responseCode in 200..299 ||
                    operation.action == PlanSyncQueue.ACTION_DELETE &&
                    responseCode == HttpURLConnection.HTTP_NOT_FOUND
                when {
                    accepted -> PlanSyncQueue.markCompleted(applicationContext, userUid, operation)
                    responseCode == 408 || responseCode == 429 || responseCode >= 500 -> return Result.retry()
                    else -> return Result.failure()
                }
            }

            // A new edit may have arrived while this worker was uploading. Let the
            // replacement worker send it before pulling, so remote data never
            // overwrites a pending local change.
            if (PlanSyncQueue.load(applicationContext, userUid).isNotEmpty()) {
                return Result.retry()
            }

            var response = fetchPlans(baseUrl, tokens)
            if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                tokens = BackendAuthTokenProvider.get(user, forceRefresh = true)
                response = fetchPlans(baseUrl, tokens)
            }
            when {
                response.code in 200..299 -> {
                    if (PlanSyncQueue.load(applicationContext, userUid).isNotEmpty()) {
                        return Result.retry()
                    }
                    store.replaceFromRemote(parsePlans(response.body))
                    markBootstrapComplete(userUid)
                    Result.success()
                }
                response.code == 408 || response.code == 429 || response.code >= 500 -> {
                    Result.retry()
                }
                else -> Result.failure()
            }
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun enqueueLegacyLocalTasksIfNeeded(userUid: String, store: PlanStore) {
        if (isBootstrapComplete(userUid)) return
        if (PlanSyncQueue.load(applicationContext, userUid).isNotEmpty()) return
        val localTasks = store.load()
        if (localTasks.isNotEmpty()) {
            PlanSyncQueue.enqueueChanges(
                applicationContext,
                userUid,
                previous = emptyList(),
                current = localTasks
            )
        }
    }

    private fun fetchPlans(
        baseUrl: String,
        tokens: BackendAuthTokens
    ): SyncHttpResponse {
        val connection = URL("$baseUrl/api/v1/plans").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer ${tokens.idToken}")
            connection.setRequestProperty("X-Firebase-AppCheck", tokens.appCheckToken)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            SyncHttpResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun parsePlans(body: String): List<PlanTask> {
        val plans = JSONArray(body)
        val tasks = mutableListOf<Pair<Int, PlanTask>>()
        repeat(plans.length()) { planIndex ->
            val plan = plans.getJSONObject(planIndex)
            val dateKey = plan.getString("plan_date")
            val planTasks = plan.optJSONArray("tasks") ?: JSONArray()
            repeat(planTasks.length()) { taskIndex ->
                val item = planTasks.getJSONObject(taskIndex)
                val status = item.optString("status", "pending")
                tasks += item.optInt("order_index", taskIndex) to PlanTask(
                    id = item.getString("id"),
                    title = item.getString("title"),
                    durationMinutes = item.optInt("estimated_minutes", 30).coerceIn(0, 1440),
                    priority = item.optInt("priority", 3).coerceIn(1, 5),
                    type = item.optString("task_type", "Learning").ifBlank { "Learning" },
                    dateKey = dateKey,
                    completed = status == "completed",
                    isBreak = item.optBoolean("is_break", false),
                    scheduledStart = optionalTime(item, "scheduled_start"),
                    scheduledEnd = optionalTime(item, "scheduled_end")
                )
            }
        }
        return tasks
            .sortedWith(compareBy<Pair<Int, PlanTask>> { it.second.dateKey }.thenBy { it.first })
            .map { it.second }
    }

    private fun optionalTime(item: JSONObject, key: String): String? {
        if (!item.has(key) || item.isNull(key)) return null
        return item.optString(key)
            .takeIf { it.isNotBlank() && it != "null" }
            ?.take(5)
    }

    private fun isBootstrapComplete(userUid: String): Boolean =
        syncPreferences(userUid).getBoolean(KEY_BOOTSTRAP_COMPLETE, false)

    private fun markBootstrapComplete(userUid: String) {
        syncPreferences(userUid).edit().putBoolean(KEY_BOOTSTRAP_COMPLETE, true).apply()
    }

    private fun syncPreferences(userUid: String) = applicationContext.getSharedPreferences(
        "zen_plan_pull_$userUid",
        Context.MODE_PRIVATE
    )

    private fun sendOperation(
        baseUrl: String,
        tokens: BackendAuthTokens,
        operation: PendingTaskSync
    ): Int {
        val path = if (operation.action == PlanSyncQueue.ACTION_DELETE) {
            "/api/v1/tasks/${operation.taskId}"
        } else {
            "/api/v1/plans/${operation.dateKey}/tasks/${operation.taskId}"
        }
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = if (operation.action == PlanSyncQueue.ACTION_DELETE) {
                "DELETE"
            } else {
                "PUT"
            }
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer ${tokens.idToken}")
            connection.setRequestProperty("X-Firebase-AppCheck", tokens.appCheckToken)
            connection.setRequestProperty("Accept", "application/json")
            if (operation.action == PlanSyncQueue.ACTION_UPSERT) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val body = taskBody(operation).toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            connection.responseCode.also {
                (if (it >= 400) connection.errorStream else connection.inputStream)?.close()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun taskBody(operation: PendingTaskSync): JSONObject {
        val task = requireNotNull(operation.task)
        return JSONObject().apply {
            put("title", task.title)
            put("task_type", task.type)
            put("priority", task.priority)
            put("estimated_minutes", task.durationMinutes)
            put("scheduled_start", task.scheduledStart ?: JSONObject.NULL)
            put("scheduled_end", task.scheduledEnd ?: JSONObject.NULL)
            put("is_fixed_time", !task.scheduledStart.isNullOrBlank())
            put("is_break", task.isBreak)
            put("order_index", operation.orderIndex)
            put("status", if (task.completed) "completed" else "pending")
        }
    }

    companion object {
        const val KEY_USER_UID = "user_uid"
        private const val TIMEOUT_MS = 15_000
        private const val KEY_BOOTSTRAP_COMPLETE = "bootstrap_complete"
    }

    private data class SyncHttpResponse(val code: Int, val body: String)
}
