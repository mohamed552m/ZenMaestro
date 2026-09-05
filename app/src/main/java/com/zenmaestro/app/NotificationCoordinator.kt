package com.zenmaestro.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object NotificationCoordinator {

    const val PROFILE_PREFERENCES = "zen_profile_preferences"
    const val KEY_REMINDERS = "learning_reminders"
    const val CHANNEL_ID = "learning_reminders"

    private const val WORK_TAG = "zen_learning_reminders"
    private const val WORK_PREFIX = "zen_task_reminder_"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun remindersEnabled(context: Context): Boolean = context.getSharedPreferences(
        PROFILE_PREFERENCES,
        Context.MODE_PRIVATE
    ).getBoolean(KEY_REMINDERS, true)

    fun setRemindersEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PROFILE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REMINDERS, enabled)
            .apply()
        if (!enabled) cancelAll(context)
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    fun sync(context: Context, tasks: List<PlanTask>) {
        val appContext = context.applicationContext
        cancelAll(appContext)
        if (!remindersEnabled(appContext)) return

        val now = System.currentTimeMillis()
        tasks.asSequence()
            .filter { !it.completed && !it.isBreak && !it.scheduledStart.isNullOrBlank() }
            .forEach { task ->
                val reminderAt = scheduledTime(task) ?: return@forEach
                val delay = reminderAt - now
                if (delay <= 0L) return@forEach

                val data = Data.Builder()
                    .putString(LearningReminderWorker.KEY_TASK_ID, task.id)
                    .putString(LearningReminderWorker.KEY_TASK_TITLE, task.title)
                    .putInt(LearningReminderWorker.KEY_DURATION, task.durationMinutes)
                    .build()
                val request = OneTimeWorkRequestBuilder<LearningReminderWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(data)
                    .addTag(WORK_TAG)
                    .build()
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    WORK_PREFIX + task.id,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(WORK_TAG)
    }

    fun nextScheduledTask(tasks: List<PlanTask>): Pair<PlanTask, Long>? = tasks.asSequence()
        .filter { !it.completed && !it.isBreak && !it.scheduledStart.isNullOrBlank() }
        .mapNotNull { task -> scheduledTime(task)?.let { task to it } }
        .filter { (_, time) -> time > System.currentTimeMillis() }
        .minByOrNull { (_, time) -> time }

    @SuppressLint("MissingPermission")
    fun showTaskNotification(context: Context, taskId: String, title: String, duration: Int): Boolean {
        if (!hasPermission(context) || !remindersEnabled(context)) return false
        createChannel(context)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            taskId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setColor(ContextCompat.getColor(context, R.color.chalkboard))
            .setContentTitle(context.getString(R.string.notification_ready_title))
            .setContentText(context.getString(R.string.notification_task_message, title, duration))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.notification_task_message, title, duration)
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(taskId.hashCode(), notification)
        return true
    }

    fun formatReminderTime(time: Long): String =
        SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.ENGLISH).format(Date(time))

    private fun scheduledTime(task: PlanTask): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            isLenient = false
        }.parse("${task.dateKey} ${task.scheduledStart}")?.time
    }.getOrNull()
}
