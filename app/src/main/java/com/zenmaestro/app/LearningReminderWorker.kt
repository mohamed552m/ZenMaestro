package com.zenmaestro.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class LearningReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID).orEmpty()
        val title = inputData.getString(KEY_TASK_TITLE).orEmpty()
        val duration = inputData.getInt(KEY_DURATION, 0)
        if (taskId.isBlank() || title.isBlank()) return Result.failure()
        NotificationCoordinator.showTaskNotification(
            applicationContext,
            taskId,
            title,
            duration
        )
        return Result.success()
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_TASK_TITLE = "task_title"
        const val KEY_DURATION = "duration"
    }
}
