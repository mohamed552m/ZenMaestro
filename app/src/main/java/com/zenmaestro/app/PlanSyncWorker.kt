package com.zenmaestro.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
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
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

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
    }
}
