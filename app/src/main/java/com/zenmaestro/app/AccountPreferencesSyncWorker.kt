package com.zenmaestro.app

import android.content.Context
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject

class AccountPreferencesSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : BackendAuthenticatedWorker(appContext, params) {

    override fun doWork(): Result {
        val userUid = inputData.getString(KEY_USER_UID).orEmpty()
        return executeAuthenticated(userUid) { baseUrl, tokens ->
            val body = JSONObject().apply {
                put("timezone", inputData.getString(KEY_TIMEZONE).orEmpty())
                put(
                    "learning_day_start",
                    String.format(
                        Locale.US,
                        "%02d:%02d:00",
                        inputData.getInt(KEY_START_HOUR, 9),
                        inputData.getInt(KEY_START_MINUTE, 0)
                    )
                )
                put("reminders_enabled", inputData.getBoolean(KEY_REMINDERS, true))
            }.toString().toByteArray(Charsets.UTF_8)
            val connection = URL("$baseUrl/api/v1/account/preferences")
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "PATCH"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer ${tokens.idToken}")
                connection.setRequestProperty("X-Firebase-AppCheck", tokens.appCheckToken)
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                connection.responseCode.also {
                    (if (it >= 400) connection.errorStream else connection.inputStream)?.close()
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        const val KEY_USER_UID = "user_uid"
        const val KEY_START_HOUR = "start_hour"
        const val KEY_START_MINUTE = "start_minute"
        const val KEY_REMINDERS = "reminders"
        const val KEY_TIMEZONE = "timezone"
        private const val TIMEOUT_MS = 15_000
    }
}
