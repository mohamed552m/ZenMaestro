package com.coachai.android.data.remote

import com.coachai.android.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ProfileData(
    val userId: String,
    val displayName: String,
    val email: String,
    val timezone: String,
    val createdAt: String,
)

sealed interface ProfileRemoteResult<out T> {
    data class Success<T>(val value: T) : ProfileRemoteResult<T>
    data object Unauthorized : ProfileRemoteResult<Nothing>
    data object Forbidden : ProfileRemoteResult<Nothing>
    data object NetworkError : ProfileRemoteResult<Nothing>
    data object InvalidResponse : ProfileRemoteResult<Nothing>
}

interface ProfileRemoteDataSource {
    suspend fun getProfile(idToken: String): ProfileRemoteResult<ProfileData>
    suspend fun updateDisplayName(
        idToken: String,
        displayName: String,
        idempotencyKey: String,
    ): ProfileRemoteResult<ProfileData>

    suspend fun updateTimezone(
        idToken: String,
        timezone: String,
        idempotencyKey: String,
    ): ProfileRemoteResult<String>
}

class ProfileApi(
    private val baseUrl: String,
    private val allowDebugCleartext: Boolean = BuildConfig.DEBUG,
) : ProfileRemoteDataSource {
    override suspend fun getProfile(idToken: String): ProfileRemoteResult<ProfileData> =
        request("/v1/profile", "GET", idToken, parser = ::parseProfile)

    override suspend fun updateDisplayName(
        idToken: String,
        displayName: String,
        idempotencyKey: String,
    ): ProfileRemoteResult<ProfileData> = request(
        path = "/v1/profile/display-name",
        method = "PATCH",
        idToken = idToken,
        idempotencyKey = idempotencyKey,
        body = JSONObject().put("display_name", displayName).toString(),
        parser = ::parseProfile,
    )

    override suspend fun updateTimezone(
        idToken: String,
        timezone: String,
        idempotencyKey: String,
    ): ProfileRemoteResult<String> = request(
        path = "/v1/profile/timezone",
        method = "PATCH",
        idToken = idToken,
        idempotencyKey = idempotencyKey,
        body = JSONObject().put("timezone", timezone).toString(),
        parser = ::parseTimezone,
    )

    private suspend fun <T> request(
        path: String,
        method: String,
        idToken: String,
        idempotencyKey: String? = null,
        body: String? = null,
        parser: (String) -> T,
    ): ProfileRemoteResult<T> = withContext(Dispatchers.IO) {
        try {
            require(idToken.isNotBlank())
            idempotencyKey?.let { require(it.isNotBlank() && it.length <= 200) }
            val connection = endpoint(path).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $idToken")
                idempotencyKey?.let {
                    connection.setRequestProperty("Idempotency-Key", it)
                }
                if (body != null) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    require(bytes.size <= MAX_REQUEST_BYTES)
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(bytes) }
                }
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> ProfileRemoteResult.Success(
                        parser(connection.inputStream.readBounded()),
                    )
                    HttpURLConnection.HTTP_UNAUTHORIZED -> ProfileRemoteResult.Unauthorized
                    HttpURLConnection.HTTP_FORBIDDEN -> ProfileRemoteResult.Forbidden
                    in 500..599 -> ProfileRemoteResult.NetworkError
                    else -> ProfileRemoteResult.InvalidResponse
                }
            } finally {
                connection.disconnect()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            ProfileRemoteResult.NetworkError
        } catch (_: Exception) {
            ProfileRemoteResult.InvalidResponse
        }
    }

    private fun endpoint(path: String): URI {
        require(path.startsWith("/v1/profile"))
        val base = URI.create(baseUrl.trim().trimEnd('/'))
        require(base.host?.isNotBlank() == true)
        require(base.userInfo == null && base.query == null && base.fragment == null)
        val secure = base.scheme.equals("https", ignoreCase = true)
        val localDebugHost = base.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        require(secure || (allowDebugCleartext && localDebugHost && base.scheme == "http"))
        return URI.create("${base.toASCIIString()}$path")
    }

    private fun parseProfile(payload: String): ProfileData {
        val json = JSONObject(payload)
        val userId = json.getString("user_id").trim()
        val displayName = json.getString("display_name").trim()
        val email = json.getString("email").trim()
        val timezone = json.getString("timezone").trim()
        val createdAt = json.getString("created_at").trim()
        require(userId.isNotEmpty() && userId.length <= 128)
        require(displayName.isNotEmpty() && displayName.length <= 100)
        require(email.length <= 320 && email.contains('@'))
        require(createdAt.isNotEmpty() && createdAt.length <= 64)
        ZoneId.of(timezone)
        return ProfileData(userId, displayName, email, timezone, createdAt)
    }

    private fun parseTimezone(payload: String): String {
        val timezone = JSONObject(payload).getString("timezone").trim()
        ZoneId.of(timezone)
        return timezone
    }

    private fun java.io.InputStream.readBounded(): String = use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw IOException("Profile response is too large")
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 4_096
        const val MAX_RESPONSE_BYTES = 262_144
    }
}
