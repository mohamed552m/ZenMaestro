package com.zenmaestro.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

class ReflectionSyncWorker(
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
            for (operation in ReflectionSyncQueue.load(applicationContext, userUid)) {
                var responseCode = send(baseUrl, tokens, operation)
                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    tokens = BackendAuthTokenProvider.get(user, forceRefresh = true)
                    responseCode = send(baseUrl, tokens, operation)
                }
                when {
                    responseCode in 200..299 -> ReflectionSyncQueue.markCompleted(
                        applicationContext,
                        userUid,
                        operation
                    )
                    responseCode == HttpURLConnection.HTTP_NOT_FOUND ||
                        responseCode == 408 ||
                        responseCode == 429 ||
                        responseCode >= 500 -> return Result.retry()
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

    private fun send(
        baseUrl: String,
        tokens: BackendAuthTokens,
        operation: PendingReflectionSync
    ): Int {
        val reflection = operation.reflection
        val connection = URL(
            "$baseUrl/api/v1/tasks/${reflection.taskId}/reflection"
        ).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "PUT"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${tokens.idToken}")
            connection.setRequestProperty("X-Firebase-AppCheck", tokens.appCheckToken)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject().apply {
                put("effort", reflection.effort)
                put("note", reflection.note)
                put("completed_at", formatTimestamp(reflection.completedAt))
            }.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            connection.responseCode.also {
                (if (it >= 400) connection.errorStream else connection.inputStream)?.close()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun formatTimestamp(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp))

    companion object {
        const val KEY_USER_UID = "user_uid"
        private const val TIMEOUT_MS = 15_000
    }
}
