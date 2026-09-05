package com.zenmaestro.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PendingTaskSync(
    val version: String,
    val action: String,
    val taskId: String,
    val dateKey: String,
    val orderIndex: Int,
    val task: PlanTask?
)

object PlanSyncQueue {

    const val ACTION_UPSERT = "upsert"
    const val ACTION_DELETE = "delete"

    @Synchronized
    fun enqueueChanges(
        context: Context,
        userUid: String,
        previous: List<PlanTask>,
        current: List<PlanTask>
    ) {
        val operations = loadMutable(context, userUid)
        val previousById = previous.associateBy { it.id }
        val currentIds = current.mapTo(mutableSetOf()) { it.id }

        previous.forEachIndexed { index, task ->
            if (task.id !in currentIds) {
                operations[task.id] = PendingTaskSync(
                    version = UUID.randomUUID().toString(),
                    action = ACTION_DELETE,
                    taskId = task.id,
                    dateKey = task.dateKey,
                    orderIndex = index,
                    task = null
                )
            }
        }

        current.forEachIndexed { index, task ->
            val oldTask = previousById[task.id]
            val oldIndex = previous.indexOfFirst { it.id == task.id }
            if (oldTask != task || oldIndex != index) {
                operations[task.id] = PendingTaskSync(
                    version = UUID.randomUUID().toString(),
                    action = ACTION_UPSERT,
                    taskId = task.id,
                    dateKey = task.dateKey,
                    orderIndex = index,
                    task = task
                )
            }
        }
        persist(context, userUid, operations.values)
    }

    @Synchronized
    fun load(context: Context, userUid: String): List<PendingTaskSync> =
        loadMutable(context, userUid).values.toList()

    @Synchronized
    fun markCompleted(context: Context, userUid: String, completed: PendingTaskSync) {
        val operations = loadMutable(context, userUid)
        if (operations[completed.taskId]?.version == completed.version) {
            operations.remove(completed.taskId)
            persist(context, userUid, operations.values)
        }
    }

    private fun loadMutable(
        context: Context,
        userUid: String
    ): LinkedHashMap<String, PendingTaskSync> = runCatching {
        val raw = preferences(context, userUid).getString(KEY_OPERATIONS, "[]") ?: "[]"
        val array = JSONArray(raw)
        LinkedHashMap<String, PendingTaskSync>().apply {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val action = item.getString("action")
                val taskJson = item.optJSONObject("task")
                val task = taskJson?.let(::taskFromJson)
                val operation = PendingTaskSync(
                    version = item.getString("version"),
                    action = action,
                    taskId = item.getString("taskId"),
                    dateKey = item.getString("dateKey"),
                    orderIndex = item.optInt("orderIndex", 0),
                    task = task
                )
                put(operation.taskId, operation)
            }
        }
    }.getOrDefault(linkedMapOf())

    private fun persist(
        context: Context,
        userUid: String,
        operations: Collection<PendingTaskSync>
    ) {
        val array = JSONArray()
        operations.forEach { operation ->
            array.put(
                JSONObject().apply {
                    put("version", operation.version)
                    put("action", operation.action)
                    put("taskId", operation.taskId)
                    put("dateKey", operation.dateKey)
                    put("orderIndex", operation.orderIndex)
                    put("task", operation.task?.let(::taskToJson) ?: JSONObject.NULL)
                }
            )
        }
        preferences(context, userUid).edit().putString(KEY_OPERATIONS, array.toString()).apply()
    }

    private fun taskToJson(task: PlanTask): JSONObject = JSONObject().apply {
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

    private fun taskFromJson(item: JSONObject): PlanTask = PlanTask(
        id = item.getString("id"),
        title = item.getString("title"),
        durationMinutes = item.getInt("duration"),
        priority = item.optInt("priority", 3).coerceIn(1, 5),
        type = item.optString("type", "Learning"),
        dateKey = item.getString("date"),
        completed = item.optBoolean("completed", false),
        isBreak = item.optBoolean("isBreak", false),
        scheduledStart = item.optString("scheduledStart").takeIf(String::isNotBlank),
        scheduledEnd = item.optString("scheduledEnd").takeIf(String::isNotBlank)
    )

    private fun preferences(context: Context, userUid: String) =
        context.applicationContext.getSharedPreferences(
            "${PREFERENCES_PREFIX}_$userUid",
            Context.MODE_PRIVATE
        )

    private const val PREFERENCES_PREFIX = "zen_plan_sync"
    private const val KEY_OPERATIONS = "operations"
}
