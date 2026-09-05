package com.zenmaestro.app

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import org.json.JSONObject

data class LearningReflection(
    val taskId: String,
    val effort: String,
    val note: String,
    val completedAt: Long
)

class ReflectionStore(context: Context) {

    private val preferences = context.getSharedPreferences(
        "zen_reflections_${FirebaseAuth.getInstance().currentUser?.uid ?: "guest"}",
        Context.MODE_PRIVATE
    )

    fun add(reflection: LearningReflection) {
        val entries = load().toMutableList().apply { add(reflection) }
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("taskId", entry.taskId)
                    put("effort", entry.effort)
                    put("note", entry.note)
                    put("completedAt", entry.completedAt)
                }
            )
        }
        preferences.edit().putString(KEY_REFLECTIONS, array.toString()).apply()
    }

    fun load(): List<LearningReflection> {
        val raw = preferences.getString(KEY_REFLECTIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                LearningReflection(
                    taskId = item.optString("taskId"),
                    effort = item.optString("effort"),
                    note = item.optString("note"),
                    completedAt = item.optLong("completedAt")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clear() {
        preferences.edit().remove(KEY_REFLECTIONS).apply()
    }

    companion object {
        private const val KEY_REFLECTIONS = "entries"
    }
}
