package com.coachai.android.data.remote

import com.coachai.android.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface NotificationDeviceRemoteResult {
    data object Success : NotificationDeviceRemoteResult
    data object Unauthorized : NotificationDeviceRemoteResult
    data object Forbidden : NotificationDeviceRemoteResult
    data object Failure : NotificationDeviceRemoteResult
}

class NotificationDeviceApi(
    private val baseUrl: String,
    private val allowDebugCleartext: Boolean = BuildConfig.DEBUG,
) {
    suspend fun register(
        idToken: String,
        deviceId: String,
        pushToken: String,
        appVersion: String,
        locale: String,
        timezone: String,
        idempotencyKey: String,
    ): NotificationDeviceRemoteResult {
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("push_token", pushToken)
            .put("app_identifier", BuildConfig.APPLICATION_ID)
            .put("app_version", appVersion)
            .put("locale", locale)
            .put("timezone", timezone)
            .toString()
        return request("POST", "/v1/notifications/devices", idToken, idempotencyKey, body)
    }

    suspend fun invalidate(
        idToken: String,
        deviceId: String,
        idempotencyKey: String,
    ): NotificationDeviceRemoteResult {
        val encodedId = URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        return request("DELETE", "/v1/notifications/devices/$encodedId", idToken, idempotencyKey)
    }

    private suspend fun request(
        method: String,
        path: String,
        idToken: String,
        idempotencyKey: String,
        body: String? = null,
    ): NotificationDeviceRemoteResult = withContext(Dispatchers.IO) {
        try {
            require(idToken.isNotBlank() && idempotencyKey.isNotBlank())
            require(idempotencyKey.length <= 200)
            val bytes = body?.toByteArray(Charsets.UTF_8)
            require(bytes == null || bytes.size <= 8 * 1_024)
            val connection = endpoint(path).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $idToken")
                connection.setRequestProperty("Idempotency-Key", idempotencyKey)
                if (bytes != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(bytes) }
                }
                when (connection.responseCode) {
                    in 200..299 -> NotificationDeviceRemoteResult.Success
                    HttpURLConnection.HTTP_UNAUTHORIZED -> NotificationDeviceRemoteResult.Unauthorized
                    HttpURLConnection.HTTP_FORBIDDEN -> NotificationDeviceRemoteResult.Forbidden
                    else -> NotificationDeviceRemoteResult.Failure
                }
            } finally {
                connection.disconnect()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            NotificationDeviceRemoteResult.Failure
        } catch (_: Exception) {
            NotificationDeviceRemoteResult.Failure
        }
    }

    private fun endpoint(path: String): URI {
        val base = URI.create(baseUrl.trim().trimEnd('/'))
        require(base.host?.isNotBlank() == true)
        require(base.userInfo == null && base.query == null && base.fragment == null)
        val secure = base.scheme.equals("https", ignoreCase = true)
        val local = base.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        require(secure || (allowDebugCleartext && local && base.scheme == "http"))
        return URI.create("${base.toASCIIString()}$path")
    }
}
