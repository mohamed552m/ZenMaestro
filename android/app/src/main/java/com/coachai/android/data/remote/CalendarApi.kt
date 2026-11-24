package com.coachai.android.data.remote

import com.coachai.android.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class CalendarRemoteItem(
    val calendarId: String,
    val name: String,
    val color: String,
    val isPrimary: Boolean,
    val selected: Boolean,
)

data class CalendarRemoteConnection(
    val connected: Boolean,
    val selectedCalendars: List<CalendarRemoteItem>,
)

sealed interface CalendarRemoteResult<out T> {
    data class Success<T>(val value: T) : CalendarRemoteResult<T>
    data object Unauthorized : CalendarRemoteResult<Nothing>
    data object Forbidden : CalendarRemoteResult<Nothing>
    data object NetworkError : CalendarRemoteResult<Nothing>
    data object ConfigurationError : CalendarRemoteResult<Nothing>
    data object InvalidResponse : CalendarRemoteResult<Nothing>
}

interface CalendarRemoteDataSource {
    suspend fun connection(token: String): CalendarRemoteResult<CalendarRemoteConnection>
    suspend fun beginAuthorization(token: String): CalendarRemoteResult<String>
    suspend fun completeAuthorization(token: String, code: String, state: String): CalendarRemoteResult<CalendarRemoteConnection>
    suspend fun calendars(token: String): CalendarRemoteResult<List<CalendarRemoteItem>>
    suspend fun select(token: String, calendars: List<CalendarRemoteItem>, key: String): CalendarRemoteResult<CalendarRemoteConnection>
    suspend fun sync(token: String, start: LocalDate, end: LocalDate, key: String): CalendarRemoteResult<Unit>
    suspend fun disconnect(token: String, key: String): CalendarRemoteResult<CalendarRemoteConnection>
}

class CalendarApi(
    private val baseUrl: String,
    private val allowDebugCleartext: Boolean = BuildConfig.DEBUG,
) : CalendarRemoteDataSource {
    override suspend fun connection(token: String) = request("GET", "", token).map(::parseConnection)

    override suspend fun beginAuthorization(token: String) =
        request("POST", "/oauth/start", token).map { JSONObject(it).getString("authorization_url") }

    override suspend fun completeAuthorization(
        token: String,
        code: String,
        state: String,
    ): CalendarRemoteResult<CalendarRemoteConnection> = request(
        "POST",
        "/oauth/complete",
        token,
        body = JSONObject().put("code", code).put("state", state).toString(),
    ).map(::parseConnection)

    override suspend fun calendars(token: String) =
        request("GET", "/calendars", token).map(::parseCalendars)

    override suspend fun select(token: String, calendars: List<CalendarRemoteItem>, key: String): CalendarRemoteResult<CalendarRemoteConnection> {
        val items = JSONArray()
        calendars.forEach { item ->
            items.put(JSONObject().apply {
                put("calendar_id", item.calendarId)
                put("name", item.name)
                put("color", item.color)
                put("is_primary", item.isPrimary)
                put("selected", true)
            })
        }
        return request("PATCH", "/calendars", token, key, JSONObject().put("calendars", items).toString())
            .map(::parseConnection)
    }

    override suspend fun sync(token: String, start: LocalDate, end: LocalDate, key: String): CalendarRemoteResult<Unit> =
        request(
            "POST", "/sync", token, key,
            JSONObject().put("range_start", start.toString()).put("range_end", end.toString()).toString(),
        ).withoutPayload()

    override suspend fun disconnect(token: String, key: String) =
        request("POST", "/disconnect", token, key).map(::parseConnection)

    private suspend fun request(
        method: String,
        path: String,
        token: String,
        idempotencyKey: String? = null,
        body: String? = null,
    ): CalendarRemoteResult<String> = withContext(Dispatchers.IO) {
        try {
            require(token.isNotBlank())
            val bodyBytes = body?.toByteArray(Charsets.UTF_8)
            require(bodyBytes == null || bodyBytes.size <= MAX_REQUEST_BYTES)
            val connection = endpoint(path).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 25_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")
                idempotencyKey?.let {
                    require(it.isNotBlank() && it.length <= 200)
                    connection.setRequestProperty("Idempotency-Key", it)
                }
                if (bodyBytes != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(bodyBytes) }
                }
                when (connection.responseCode) {
                    in 200..299 -> CalendarRemoteResult.Success(connection.inputStream.readBounded())
                    HttpURLConnection.HTTP_UNAUTHORIZED -> CalendarRemoteResult.Unauthorized
                    HttpURLConnection.HTTP_FORBIDDEN -> CalendarRemoteResult.Forbidden
                    HttpURLConnection.HTTP_UNAVAILABLE -> {
                        val code = connection.errorStream?.readBounded()?.let {
                            runCatching { JSONObject(it).optString("code") }.getOrNull()
                        }
                        if (code == "CONFIGURATION_ERROR") CalendarRemoteResult.ConfigurationError
                        else CalendarRemoteResult.NetworkError
                    }
                    in 500..599 -> CalendarRemoteResult.NetworkError
                    else -> CalendarRemoteResult.InvalidResponse
                }
            } finally {
                connection.disconnect()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            CalendarRemoteResult.NetworkError
        } catch (_: Exception) {
            CalendarRemoteResult.InvalidResponse
        }
    }

    private fun endpoint(path: String): URI {
        val base = URI.create(baseUrl.trim().trimEnd('/'))
        require(base.host?.isNotBlank() == true && base.userInfo == null && base.query == null && base.fragment == null)
        val secure = base.scheme.equals("https", true)
        val local = base.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        require(secure || (allowDebugCleartext && local && base.scheme == "http"))
        return URI.create("${base.toASCIIString()}/v1/calendar$path")
    }

    private fun parseConnection(payload: String): CalendarRemoteConnection {
        val json = JSONObject(payload)
        require(json.getString("provider") == "google")
        return CalendarRemoteConnection(
            connected = json.getBoolean("connected"),
            selectedCalendars = parseCalendars(json.getJSONArray("selected_calendars").toString()),
        )
    }

    private fun parseCalendars(payload: String): List<CalendarRemoteItem> {
        val array = JSONArray(payload)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            CalendarRemoteItem(
                calendarId = item.getString("calendar_id").also { require(it.isNotBlank()) },
                name = item.getString("name"),
                color = item.optString("color", "#4285F4"),
                isPrimary = item.optBoolean("is_primary"),
                selected = item.optBoolean("selected"),
            )
        }
    }

    private fun <T, R> CalendarRemoteResult<T>.map(transform: (T) -> R): CalendarRemoteResult<R> = when (this) {
        is CalendarRemoteResult.Success -> try {
            CalendarRemoteResult.Success(transform(value))
        } catch (_: Exception) {
            CalendarRemoteResult.InvalidResponse
        }
        CalendarRemoteResult.Unauthorized -> CalendarRemoteResult.Unauthorized
        CalendarRemoteResult.Forbidden -> CalendarRemoteResult.Forbidden
        CalendarRemoteResult.NetworkError -> CalendarRemoteResult.NetworkError
        CalendarRemoteResult.ConfigurationError -> CalendarRemoteResult.ConfigurationError
        CalendarRemoteResult.InvalidResponse -> CalendarRemoteResult.InvalidResponse
    }

    private fun CalendarRemoteResult<String>.withoutPayload(): CalendarRemoteResult<Unit> = when (this) {
        is CalendarRemoteResult.Success -> CalendarRemoteResult.Success(Unit)
        CalendarRemoteResult.Unauthorized -> CalendarRemoteResult.Unauthorized
        CalendarRemoteResult.Forbidden -> CalendarRemoteResult.Forbidden
        CalendarRemoteResult.NetworkError -> CalendarRemoteResult.NetworkError
        CalendarRemoteResult.ConfigurationError -> CalendarRemoteResult.ConfigurationError
        CalendarRemoteResult.InvalidResponse -> CalendarRemoteResult.InvalidResponse
    }

    private fun java.io.InputStream.readBounded(): String = use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw IOException("Calendar response is too large")
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 128 * 1_024
        const val MAX_RESPONSE_BYTES = 512 * 1_024
    }
}
