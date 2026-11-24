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

data class CoachRecommendation(
    val summary: String,
    val strengths: List<String>,
    val weaknesses: List<String>,
    val recommendations: List<String>,
)

sealed interface CoachRemoteResult<out T> {
    data class Success<T>(val value: T) : CoachRemoteResult<T>
    data object Unauthorized : CoachRemoteResult<Nothing>
    data object Forbidden : CoachRemoteResult<Nothing>
    data object NetworkError : CoachRemoteResult<Nothing>
    data object ConfigurationError : CoachRemoteResult<Nothing>
    data object InvalidResponse : CoachRemoteResult<Nothing>
}

interface CoachRemoteDataSource {
    suspend fun recommendations(token: String): CoachRemoteResult<CoachRecommendation>
    suspend fun chat(token: String, message: String): CoachRemoteResult<String>
}

class CoachApi(
    private val baseUrl: String,
    private val allowDebugCleartext: Boolean = BuildConfig.DEBUG,
) : CoachRemoteDataSource {
    override suspend fun recommendations(token: String) =
        request("GET", "/recommendations/today", token).map(::parseRecommendation)

    override suspend fun chat(token: String, message: String): CoachRemoteResult<String> {
        val clean = message.trim()
        if (clean.isEmpty() || clean.length > 10_000) return CoachRemoteResult.InvalidResponse
        return request("POST", "/chat", token, JSONObject().put("message", clean).toString())
            .map { JSONObject(it).getString("reply").also { reply -> require(reply.isNotBlank()) } }
    }

    private suspend fun request(method: String, path: String, token: String, body: String? = null): CoachRemoteResult<String> =
        withContext(Dispatchers.IO) {
            try {
                require(token.isNotBlank())
                val bytes = body?.toByteArray(Charsets.UTF_8)
                require(bytes == null || bytes.size <= 64 * 1_024)
                val connection = endpoint(path).toURL().openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = method
                    connection.connectTimeout = 8_000
                    // AI requests can require multiple grounded tool calls. Keep the
                    // client deadline above the backend's bounded provider fallback.
                    connection.readTimeout = 90_000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    if (bytes != null) {
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(bytes) }
                    }
                    when (connection.responseCode) {
                        HttpURLConnection.HTTP_OK -> CoachRemoteResult.Success(connection.inputStream.readBounded())
                        HttpURLConnection.HTTP_UNAUTHORIZED -> CoachRemoteResult.Unauthorized
                        HttpURLConnection.HTTP_FORBIDDEN -> CoachRemoteResult.Forbidden
                        HttpURLConnection.HTTP_UNAVAILABLE -> CoachRemoteResult.ConfigurationError
                        in 500..599 -> CoachRemoteResult.NetworkError
                        else -> CoachRemoteResult.InvalidResponse
                    }
                } finally { connection.disconnect() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                CoachRemoteResult.NetworkError
            } catch (_: Exception) {
                CoachRemoteResult.InvalidResponse
            }
        }

    private fun endpoint(path: String): URI {
        val base = URI.create(baseUrl.trim().trimEnd('/'))
        require(base.host?.isNotBlank() == true && base.userInfo == null && base.query == null && base.fragment == null)
        val secure = base.scheme.equals("https", true)
        val local = base.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        require(secure || (allowDebugCleartext && local && base.scheme == "http"))
        return URI.create("${base.toASCIIString()}/v1/coach$path")
    }

    private fun parseRecommendation(payload: String): CoachRecommendation {
        val json = JSONObject(payload)
        fun strings(name: String): List<String> {
            val array = json.getJSONArray(name)
            require(array.length() <= 5)
            return List(array.length()) { array.getString(it).also { value -> require(value.isNotBlank()) } }
        }
        return CoachRecommendation(
            summary = json.getString("summary").also { require(it.isNotBlank()) },
            strengths = strings("strengths"),
            weaknesses = strings("weaknesses"),
            recommendations = strings("recommendations"),
        )
    }

    private fun <T, R> CoachRemoteResult<T>.map(transform: (T) -> R): CoachRemoteResult<R> = when (this) {
        is CoachRemoteResult.Success -> runCatching { CoachRemoteResult.Success(transform(value)) }.getOrDefault(CoachRemoteResult.InvalidResponse)
        CoachRemoteResult.Unauthorized -> CoachRemoteResult.Unauthorized
        CoachRemoteResult.Forbidden -> CoachRemoteResult.Forbidden
        CoachRemoteResult.NetworkError -> CoachRemoteResult.NetworkError
        CoachRemoteResult.ConfigurationError -> CoachRemoteResult.ConfigurationError
        CoachRemoteResult.InvalidResponse -> CoachRemoteResult.InvalidResponse
    }

    private fun java.io.InputStream.readBounded(): String = use { input ->
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8_192); var total = 0
        while (true) { val count = input.read(buffer); if (count < 0) break; total += count; if (total > 512 * 1_024) throw IOException(); output.write(buffer, 0, count) }
        output.toString(Charsets.UTF_8.name())
    }
}
