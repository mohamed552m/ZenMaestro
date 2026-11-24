package com.coachai.android.data.remote

import com.coachai.android.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface AnalyticsRemoteResult {
    data class Success(val payload: String) : AnalyticsRemoteResult
    data object Unauthorized : AnalyticsRemoteResult
    data object Forbidden : AnalyticsRemoteResult
    data object NetworkError : AnalyticsRemoteResult
    data object InvalidResponse : AnalyticsRemoteResult
}

fun interface AnalyticsRemoteDataSource {
    suspend fun profile(idToken: String, windowDays: Int, language: String): AnalyticsRemoteResult
}

class AnalyticsApi(
    private val baseUrl: String,
    private val allowDebugCleartext: Boolean = BuildConfig.DEBUG,
) : AnalyticsRemoteDataSource {
    override suspend fun profile(
        idToken: String,
        windowDays: Int,
        language: String,
    ): AnalyticsRemoteResult = withContext(Dispatchers.IO) {
        try {
            require(idToken.isNotBlank())
            require(windowDays in 1..365)
            require(language == "en" || language == "ar")
            val connection = endpoint(windowDays, language).toURL()
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 25_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $idToken")
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val payload = connection.inputStream.readBounded()
                        validateContract(JSONObject(payload), windowDays)
                        AnalyticsRemoteResult.Success(payload)
                    }
                    HttpURLConnection.HTTP_UNAUTHORIZED -> AnalyticsRemoteResult.Unauthorized
                    HttpURLConnection.HTTP_FORBIDDEN -> AnalyticsRemoteResult.Forbidden
                    in 500..599 -> AnalyticsRemoteResult.NetworkError
                    else -> AnalyticsRemoteResult.InvalidResponse
                }
            } finally {
                connection.disconnect()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            AnalyticsRemoteResult.NetworkError
        } catch (_: Exception) {
            AnalyticsRemoteResult.InvalidResponse
        }
    }

    private fun endpoint(windowDays: Int, language: String): URI {
        val base = URI.create(baseUrl.trim().trimEnd('/'))
        require(base.host?.isNotBlank() == true)
        require(base.userInfo == null && base.query == null && base.fragment == null)
        val secure = base.scheme.equals("https", ignoreCase = true)
        val localDebugHost = base.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        require(secure || (allowDebugCleartext && localDebugHost && base.scheme == "http"))
        return URI.create(
            "${base.toASCIIString()}/v1/analytics?window_days=$windowDays&language=$language",
        )
    }

    private fun validateContract(payload: JSONObject, requestedWindow: Int) {
        val requiredSections = listOf(
            "productivity", "priority", "planning", "categories",
            "failure_analysis", "consistency", "trend", "patterns",
            "insights", "correlations", "heatmaps", "duration_analysis",
            "weekday_weekend", "habits", "burnout", "best_hour", "capacity",
        )
        require(payload.getString("generated_at").isNotBlank())
        require(payload.getInt("sample_size") >= 0)
        require(payload.optInt("window_days", requestedWindow) == requestedWindow)
        require(requiredSections.all { payload.opt(it) is JSONObject })
    }

    private fun java.io.InputStream.readBounded(): String = use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw IOException("Analytics response is too large")
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 2 * 1_048_576
    }
}
