package com.zenmaestro.app

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import org.json.JSONObject

data class PlanTask(
    val id: String,
    val title: String,
    val durationMinutes: Int,
    val priority: Int,
    val type: String,
    val dateKey: String,
    val completed: Boolean = false,
    val isBreak: Boolean = false,
    val scheduledStart: String? = null,
    val scheduledEnd: String? = null
)

class PlanStore(context: Context) {

    private val appContext = context.applicationContext

    private val preferences = appContext.getSharedPreferences(
        "zen_plan_${FirebaseAuth.getInstance().currentUser?.uid ?: "guest"}",
        Context.MODE_PRIVATE
    )

    fun load(): MutableList<PlanTask> = runCatching {
        val stored = preferences.getString(KEY_TASKS, "[]") ?: "[]"
        val array = JSONArray(stored)
        MutableList(array.length()) { index ->
            val item = array.getJSONObject(index)
            PlanTask(
                id = item.getString("id"),
                title = item.getString("title"),
                durationMinutes = item.getInt("duration"),
                priority = item.optInt("priority", 3).coerceIn(1, 5),
                type = item.optString("type", "Learning"),
                dateKey = item.getString("date"),
                completed = item.optBoolean("completed", false),
                isBreak = item.optBoolean("isBreak", false),
                scheduledStart = item.optString("scheduledStart").takeIf { it.isNotBlank() },
                scheduledEnd = item.optString("scheduledEnd").takeIf { it.isNotBlank() }
            )
        }
    }.getOrDefault(mutableListOf())

    fun save(tasks: List<PlanTask>) {
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(
                JSONObject().apply {
                    put("id", task.id)
                    put("title", task.title)
                    put("duration", task.durationMinutes)
                    put("priority", task.priority)
                    put("type", task.type)
                    put("date", task.dateKey)
                    put("completed", task.completed)
                    put("isBreak", task.isBreak)
                    put("scheduledStart", task.scheduledStart.orEmpty())
                    put("scheduledEnd", task.scheduledEnd.orEmpty())
                }
            )
        }
        preferences.edit().putString(KEY_TASKS, array.toString()).apply()
        NotificationCoordinator.sync(appContext, tasks)
    }

    fun clear() {
        preferences.edit().remove(KEY_TASKS).apply()
        NotificationCoordinator.cancelAll(appContext)
    }

    companion object {
        private const val KEY_TASKS = "tasks"
    }
}
