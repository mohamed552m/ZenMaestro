package com.zenmaestro.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import java.net.HttpURLConnection

abstract class BackendAuthenticatedWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    protected fun executeAuthenticated(
        userUid: String,
        request: (baseUrl: String, tokens: BackendAuthTokens) -> Int
    ): Result {
        val baseUrl = BuildConfig.API_BASE_URL.trimEnd('/')
        if (userUid.isBlank() || baseUrl.isBlank()) return Result.success()
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null || user.uid != userUid) return Result.success()
        return try {
            var tokens = BackendAuthTokenProvider.get(user, forceRefresh = false)
            var responseCode = request(baseUrl, tokens)
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                tokens = BackendAuthTokenProvider.get(user, forceRefresh = true)
                responseCode = request(baseUrl, tokens)
            }
            when {
                responseCode in 200..299 -> Result.success()
                responseCode == 408 || responseCode == 429 || responseCode >= 500 -> Result.retry()
                else -> Result.failure()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
