package com.zenmaestro.app

import com.google.firebase.auth.FirebaseAuth
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ZenCoachService {

    private val history = ArrayDeque<ChatTurn>()

    suspend fun reply(message: String): String = withContext(Dispatchers.IO) {
        val baseUrl = BuildConfig.API_BASE_URL.trimEnd('/')
        require(baseUrl.isNotBlank()) { "Backend URL is not configured" }
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("Sign in is required")
        var tokens = BackendAuthTokenProvider.get(user, forceRefresh = false)

        var response = send(baseUrl, tokens, message)
        if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            tokens = BackendAuthTokenProvider.get(user, forceRefresh = true)
            response = send(baseUrl, tokens, message)
        }
        check(response.code in 200..299) { "Coach request failed (${response.code})" }

        val reply = JSONObject(response.body).optString("reply").trim()
        check(reply.isNotBlank()) { "Empty AI response" }
        history.addLast(ChatTurn("user", message))
        history.addLast(ChatTurn("model", reply))
        trimHistory()
        reply
    }

    fun resetConversation() {
        history.clear()
    }

    private fun send(
        baseUrl: String,
        tokens: BackendAuthTokens,
        message: String
    ): HttpResponse {
        val connection = URL("$baseUrl/api/v1/coach/chat").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${tokens.idToken}")
            connection.setRequestProperty("X-Firebase-AppCheck", tokens.appCheckToken)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = JSONObject().apply {
                put("message", message)
                put(
                    "history",
                    JSONArray().apply {
                        history.forEach { turn ->
                            put(
                                JSONObject().apply {
                                    put("role", turn.role)
                                    put("content", turn.content)
                                }
                            )
                        }
                    }
                )
            }.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            HttpResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun trimHistory() {
        while (history.size > MAX_HISTORY_ITEMS) history.removeFirst()
    }

    private data class ChatTurn(val role: String, val content: String)
    private data class HttpResponse(val code: Int, val body: String)

    private companion object {
        const val MAX_HISTORY_ITEMS = 20
        const val TIMEOUT_MS = 30_000
    }
}
