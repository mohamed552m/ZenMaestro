package com.zenmaestro.app

import com.google.firebase.auth.FirebaseAuth
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ZenPlanDraft(
    val summary: String,
    val tasks: List<ZenDraftTask>
)

data class ZenDraftTask(
    val title: String,
    val type: String,
    val priority: Int,
    val durationMinutes: Int
)

class ZenPlannerService {

    suspend fun createDraft(
        rawInput: String,
        existingCategories: List<String>
    ): ZenPlanDraft = withContext(Dispatchers.IO) {
        val baseUrl = BuildConfig.API_BASE_URL.trimEnd('/')
        require(baseUrl.isNotBlank()) { "Backend URL is not configured" }
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("Sign in is required")
        var tokens = BackendAuthTokenProvider.get(user, forceRefresh = false)
        var response = send(baseUrl, tokens, rawInput, existingCategories)
        if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            tokens = BackendAuthTokenProvider.get(user, forceRefresh = true)
            response = send(baseUrl, tokens, rawInput, existingCategories)
        }
        check(response.code in 200..299) { "Planner request failed (${response.code})" }
        parseDraft(response.body)
    }

    private fun send(
        baseUrl: String,
        tokens: BackendAuthTokens,
        rawInput: String,
        existingCategories: List<String>
    ): HttpResponse {
        val connection = URL("$baseUrl/api/v1/planner/draft").openConnection() as HttpURLConnection
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
                put("raw_input", rawInput)
                put("existing_categories", JSONArray(existingCategories))
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

    private fun parseDraft(body: String): ZenPlanDraft {
        val json = JSONObject(body)
        val taskArray = json.optJSONArray("tasks") ?: JSONArray()
        val tasks = buildList {
            repeat(taskArray.length()) { index ->
                val item = taskArray.getJSONObject(index)
                val title = item.optString("title").trim()
                if (title.isNotBlank()) {
                    add(
                        ZenDraftTask(
                            title = title,
                            type = item.optString("task_type", "Learning").ifBlank { "Learning" },
                            priority = item.optInt("priority", 3).coerceIn(1, 5),
                            durationMinutes = item.optInt("estimated_minutes", 30).coerceIn(0, 1440)
                        )
                    )
                }
            }
        }
        check(tasks.isNotEmpty()) { "Planner returned no tasks" }
        return ZenPlanDraft(summary = json.optString("summary").trim(), tasks = tasks)
    }

    private data class HttpResponse(val code: Int, val body: String)

    private companion object {
        const val TIMEOUT_MS = 30_000
    }
}
