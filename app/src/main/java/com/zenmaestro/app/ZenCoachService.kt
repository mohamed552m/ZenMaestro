package com.zenmaestro.app

import com.google.firebase.auth.FirebaseAuth
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class CoachAttachment(
    val displayName: String,
    val mimeType: String,
    val bytes: ByteArray
)

data class SavedCoachMessage(
    val role: String,
    val content: String,
    val attachmentName: String?
)

data class SavedCoachConversation(
    val id: String,
    val messages: List<SavedCoachMessage>
)

class ZenCoachService {

    private val history = ArrayDeque<ChatTurn>()
    private var conversationId: String? = null

    suspend fun loadLatestConversation(): SavedCoachConversation? = withContext(Dispatchers.IO) {
        val response = authenticatedRequest { baseUrl, tokens ->
            sendGet(baseUrl, tokens, "/api/v1/coach/conversations/latest")
        }
        check(response.code in 200..299) { errorMessage(response) }
        if (response.body.isBlank() || response.body.trim() == "null") return@withContext null

        val json = JSONObject(response.body)
        val messagesJson = json.optJSONArray("messages") ?: JSONArray()
        val messages = buildList {
            for (index in 0 until messagesJson.length()) {
                val item = messagesJson.getJSONObject(index)
                add(
                    SavedCoachMessage(
                        role = item.optString("role"),
                        content = item.optString("content"),
                        attachmentName = item.optNullableString("attachment_name")
                    )
                )
            }
        }
        conversationId = json.getString("id")
        history.clear()
        messages.takeLast(MAX_HISTORY_ITEMS).forEach { message ->
            history.addLast(ChatTurn(message.role, message.content))
        }
        SavedCoachConversation(id = conversationId.orEmpty(), messages = messages)
    }

    suspend fun reply(message: String, attachment: CoachAttachment? = null): String =
        withContext(Dispatchers.IO) {
            var response = authenticatedRequest { baseUrl, tokens ->
                if (attachment == null) {
                    sendJson(baseUrl, tokens, message)
                } else {
                    sendMultipart(baseUrl, tokens, message, attachment)
                }
            }
            if (response.code == HttpURLConnection.HTTP_NOT_FOUND && conversationId != null) {
                conversationId = null
                response = authenticatedRequest { baseUrl, tokens ->
                    if (attachment == null) {
                        sendJson(baseUrl, tokens, message)
                    } else {
                        sendMultipart(baseUrl, tokens, message, attachment)
                    }
                }
            }
            check(response.code in 200..299) { errorMessage(response) }

            val body = JSONObject(response.body)
            val reply = body.optString("reply").trim()
            check(reply.isNotBlank()) { "Empty AI response" }
            conversationId = body.optString("conversation_id").takeIf { it.isNotBlank() }
                ?: conversationId
            history.addLast(ChatTurn("user", message))
            history.addLast(ChatTurn("model", reply))
            trimHistory()
            reply
        }

    fun resetConversation() {
        history.clear()
        conversationId = null
    }

    private suspend fun authenticatedRequest(
        block: (String, BackendAuthTokens) -> HttpResponse
    ): HttpResponse {
        val baseUrl = BuildConfig.API_BASE_URL.trimEnd('/')
        require(baseUrl.isNotBlank()) { "Backend URL is not configured" }
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("Sign in is required")
        var tokens = BackendAuthTokenProvider.get(user, forceRefresh = false)
        var response = block(baseUrl, tokens)
        if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            tokens = BackendAuthTokenProvider.get(user, forceRefresh = true)
            response = block(baseUrl, tokens)
        }
        return response
    }

    private fun sendGet(
        baseUrl: String,
        tokens: BackendAuthTokens,
        path: String
    ): HttpResponse {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        return try {
            configure(connection, tokens)
            connection.requestMethod = "GET"
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun sendJson(
        baseUrl: String,
        tokens: BackendAuthTokens,
        message: String
    ): HttpResponse {
        val connection = URL("$baseUrl/api/v1/coach/chat").openConnection() as HttpURLConnection
        return try {
            configure(connection, tokens)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = JSONObject().apply {
                put("message", message)
                put("history", historyJson())
                conversationId?.let { put("conversation_id", it) }
            }.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun sendMultipart(
        baseUrl: String,
        tokens: BackendAuthTokens,
        message: String,
        attachment: CoachAttachment
    ): HttpResponse {
        val boundary = "ZenMaestro-${UUID.randomUUID()}"
        val connection = URL("$baseUrl/api/v1/coach/chat/attachment")
            .openConnection() as HttpURLConnection
        return try {
            configure(connection, tokens)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setChunkedStreamingMode(8192)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.outputStream.buffered().use { output ->
                fun writeText(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
                fun writeField(name: String, value: String) {
                    writeText("--$boundary\r\n")
                    writeText("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    writeText(value)
                    writeText("\r\n")
                }
                writeField("message", message)
                writeField("history_json", historyJson().toString())
                conversationId?.let { writeField("conversation_id", it) }
                val safeName = attachment.displayName
                    .replace("\r", "_")
                    .replace("\n", "_")
                    .replace("\"", "_")
                writeText("--$boundary\r\n")
                writeText(
                    "Content-Disposition: form-data; name=\"attachment\"; " +
                        "filename=\"$safeName\"\r\n"
                )
                writeText("Content-Type: ${attachment.mimeType}\r\n\r\n")
                output.write(attachment.bytes)
                writeText("\r\n--$boundary--\r\n")
            }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun configure(connection: HttpURLConnection, tokens: BackendAuthTokens) {
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Authorization", "Bearer ${tokens.idToken}")
        connection.setRequestProperty("X-Firebase-AppCheck", tokens.appCheckToken)
        connection.setRequestProperty("Accept", "application/json")
    }

    private fun readResponse(connection: HttpURLConnection): HttpResponse {
        val code = connection.responseCode
        val stream = if (code >= 400) connection.errorStream else connection.inputStream
        return HttpResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
    }

    private fun errorMessage(response: HttpResponse): String {
        val detail = runCatching { JSONObject(response.body).optString("detail") }.getOrNull()
        return detail?.takeIf { it.isNotBlank() } ?: "Coach request failed (${response.code})"
    }

    private fun historyJson() = JSONArray().apply {
        history.forEach { turn ->
            put(
                JSONObject().apply {
                    put("role", turn.role)
                    put("content", turn.content)
                }
            )
        }
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun trimHistory() {
        while (history.size > MAX_HISTORY_ITEMS) history.removeFirst()
    }

    private data class ChatTurn(val role: String, val content: String)
    private data class HttpResponse(val code: Int, val body: String)

    private companion object {
        const val MAX_HISTORY_ITEMS = 20
        const val TIMEOUT_MS = 45_000
    }
}
