package com.zenmaestro.app

import android.content.Context
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL

class AccountLearningDataDeleteWorker(
    appContext: Context,
    params: WorkerParameters
) : BackendAuthenticatedWorker(appContext, params) {

    override fun doWork(): Result {
        val userUid = inputData.getString(KEY_USER_UID).orEmpty()
        return executeAuthenticated(userUid) { baseUrl, tokens ->
            val connection = URL("$baseUrl/api/v1/account/learning-data")
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "DELETE"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Authorization", "Bearer ${tokens.idToken}")
                connection.setRequestProperty("X-Firebase-AppCheck", tokens.appCheckToken)
                connection.setRequestProperty("Accept", "application/json")
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
        private const val TIMEOUT_MS = 15_000
    }
}
