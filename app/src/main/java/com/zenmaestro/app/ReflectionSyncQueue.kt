package com.zenmaestro.app

import android.content.Context
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class PendingReflectionSync(
    val version: String,
    val reflection: LearningReflection
)

object ReflectionSyncQueue {

    @Synchronized
    fun enqueue(
        context: Context,
        userUid: String,
        reflection: LearningReflection
    ) {
        val operations = loadMutable(context, userUid)
        operations[reflection.taskId] = PendingReflectionSync(
            version = UUID.randomUUID().toString(),
            reflection = reflection
        )
        persist(context, userUid, operations.values)
    }

    @Synchronized
    fun load(context: Context, userUid: String): List<PendingReflectionSync> =
        loadMutable(context, userUid).values.toList()

    @Synchronized
    fun markCompleted(
        context: Context,
        userUid: String,
        completed: PendingReflectionSync
    ) {
        val operations = loadMutable(context, userUid)
        val taskId = completed.reflection.taskId
        if (operations[taskId]?.version == completed.version) {
            operations.remove(taskId)
            persist(context, userUid, operations.values)
        }
    }

    @Synchronized
    fun clear(context: Context, userUid: String) {
        preferences(context, userUid).edit().remove(KEY_OPERATIONS).apply()
    }

    private fun loadMutable(
        context: Context,
        userUid: String
    ): LinkedHashMap<String, PendingReflectionSync> = runCatching {
        val raw = preferences(context, userUid).getString(KEY_OPERATIONS, "[]") ?: "[]"
        val array = JSONArray(raw)
        LinkedHashMap<String, PendingReflectionSync>().apply {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val reflectionJson = item.getJSONObject("reflection")
                val reflection = LearningReflection(
                    taskId = reflectionJson.getString("taskId"),
                    effort = reflectionJson.getString("effort"),
                    note = reflectionJson.optString("note"),
                    completedAt = reflectionJson.getLong("completedAt")
                )
                put(
                    reflection.taskId,
                    PendingReflectionSync(
                        version = item.getString("version"),
                        reflection = reflection
                    )
                )
            }
        }
    }.getOrDefault(linkedMapOf())

    private fun persist(
        context: Context,
        userUid: String,
        operations: Collection<PendingReflectionSync>
    ) {
        val array = JSONArray()
        operations.forEach { operation ->
            array.put(
                JSONObject().apply {
                    put("version", operation.version)
                    put(
                        "reflection",
                        JSONObject().apply {
                            put("taskId", operation.reflection.taskId)
                            put("effort", operation.reflection.effort)
                            put("note", operation.reflection.note)
                            put("completedAt", operation.reflection.completedAt)
                        }
                    )
                }
            )
        }
        preferences(context, userUid).edit().putString(KEY_OPERATIONS, array.toString()).apply()
    }

    private fun preferences(context: Context, userUid: String) =
        context.applicationContext.getSharedPreferences(
            "${PREFERENCES_PREFIX}_$userUid",
            Context.MODE_PRIVATE
        )

    private const val PREFERENCES_PREFIX = "zen_reflection_sync"
    private const val KEY_OPERATIONS = "operations"
}
