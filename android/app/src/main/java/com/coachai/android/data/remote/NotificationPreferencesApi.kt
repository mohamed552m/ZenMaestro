package com.coachai.android.data.remote

import com.coachai.android.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class NotificationPreferences(
    val defaultReminderLeadMinutes: Int?,
    val quietHoursStart: String?,
    val quietHoursEnd: String?,
    val dailySummaryEnabled: Boolean,
    val coachCheckinsEnabled: Boolean,
    val soundEnabled: Boolean,
    val vibrationEnabled: Boolean,
)

sealed interface NotificationRemoteResult {
    data class Success(val preferences: NotificationPreferences) : NotificationRemoteResult
    data object Unauthorized : NotificationRemoteResult
    data object Forbidden : NotificationRemoteResult
    data object NetworkError : NotificationRemoteResult
    data object InvalidResponse : NotificationRemoteResult
}

interface NotificationPreferencesRemoteDataSource {
    suspend fun load(idToken: String): NotificationRemoteResult
    suspend fun update(
        idToken: String,
        changes: Map<String, Any?>,
        idempotencyKey: String,
    ): NotificationRemoteResult
}

class NotificationPreferencesApi(
    private val baseUrl: String,
    private val allowDebugCleartext: Boolean = BuildConfig.DEBUG,
) : NotificationPreferencesRemoteDataSource {
    override suspend fun load(idToken: String): NotificationRemoteResult = request("GET", idToken)

    override suspend fun update(
        idToken: String,
        changes: Map<String, Any?>,
        idempotencyKey: String,
    ): NotificationRemoteResult {
        val json = JSONObject()
        changes.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        val body = json.toString()
        if (body.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            return NotificationRemoteResult.InvalidResponse
        }
        return request("PATCH", idToken, idempotencyKey, body)
    }

    private suspend fun request(
        method: String,
        idToken: String,
        idempotencyKey: String? = null,
        body: String? = null,
    ): NotificationRemoteResult = withContext(Dispatchers.IO) {
        try {
            require(idToken.isNotBlank())
            val connection = endpoint().toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $idToken")
                idempotencyKey?.let {
                    require(it.isNotBlank() && it.length <= 200)
                    connection.setRequestProperty("Idempotency-Key", it)
                }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> NotificationRemoteResult.Success(
                        parse(connection.inputStream.readBounded()),
                    )
                    HttpURLConnection.HTTP_UNAUTHORIZED -> NotificationRemoteResult.Unauthorized
                    HttpURLConnection.HTTP_FORBIDDEN -> NotificationRemoteResult.Forbidden
                    in 500..599 -> NotificationRemoteResult.NetworkError
                    else -> NotificationRemoteResult.InvalidResponse
                }
            } finally {
                connection.disconnect()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            NotificationRemoteResult.NetworkError
        } catch (_: Exception) {
            NotificationRemoteResult.InvalidResponse
        }
    }

    private fun endpoint(): URI {
        val base = URI.create(baseUrl.trim().trimEnd('/'))
        require(base.host?.isNotBlank() == true)
        require(base.userInfo == null && base.query == null && base.fragment == null)
        val secure = base.scheme.equals("https", ignoreCase = true)
        val localDebugHost = base.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        require(secure || (allowDebugCleartext && localDebugHost && base.scheme == "http"))
        return URI.create("${base.toASCIIString()}/v1/notifications/preferences")
    }

    private fun parse(payload: String): NotificationPreferences {
        val json = JSONObject(payload)
        require(json.getString("user_id").isNotBlank())
        require(json.getString("tone") in setOf("neutral", "friendly", "coach"))
        require(json.getString("privacy_mode") in setOf("show_title", "hide_title", "generic"))
        require(json.getString("multi_device_policy") in setOf("all", "primary", "selected"))
        require(json.getString("locale") in setOf("ar", "en"))
        require(json.getString("time_format") in setOf("12h", "24h"))
        ZoneId.of(json.getString("timezone"))

        fun nullableTime(key: String): String? = if (json.isNull(key)) null else
            json.getString(key).takeIf(String::isNotBlank)?.also { LocalTime.parse(it) }
        val lead = if (json.isNull("default_reminder_lead_minutes")) null else
            json.getInt("default_reminder_lead_minutes")
        require(lead in setOf(null, 0, 5, 10, 15, 30))
        return NotificationPreferences(
            defaultReminderLeadMinutes = lead,
            quietHoursStart = nullableTime("quiet_hours_start"),
            quietHoursEnd = nullableTime("quiet_hours_end"),
            dailySummaryEnabled = json.getBoolean("daily_summary_enabled"),
            coachCheckinsEnabled = json.getBoolean("coach_checkins_enabled"),
            soundEnabled = json.getBoolean("sound_enabled"),
            vibrationEnabled = json.getBoolean("vibration_enabled"),
        )
    }

    private fun java.io.InputStream.readBounded(): String = use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw IOException("Notification response is too large")
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 8 * 1_024
        const val MAX_RESPONSE_BYTES = 256 * 1_024
    }
}
