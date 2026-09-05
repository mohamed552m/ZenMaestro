package com.zenmaestro.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class LearningMetrics(
    val allTasks: List<PlanTask>,
    val completedTasks: List<PlanTask>,
    val weekTasks: List<PlanTask>,
    val completedWeekTasks: List<PlanTask>,
    val focusedMinutes: Int,
    val streakDays: Int
) {
    val weekCompletionPercent: Int
        get() = if (weekTasks.isEmpty()) 0 else completedWeekTasks.size * 100 / weekTasks.size
}

object LearningMetricsCalculator {

    fun calculate(tasks: List<PlanTask>): LearningMetrics {
        val learningTasks = tasks.filterNot { it.isBreak }
        val completed = learningTasks.filter { it.completed }
        val weekKeys = currentWeekDateKeys()
        val weekTasks = learningTasks.filter { it.dateKey in weekKeys }
        val completedWeek = weekTasks.filter { it.completed }
        return LearningMetrics(
            allTasks = learningTasks,
            completedTasks = completed,
            weekTasks = weekTasks,
            completedWeekTasks = completedWeek,
            focusedMinutes = completed.sumOf { it.durationMinutes },
            streakDays = currentStreak(completed.map { it.dateKey }.toSet())
        )
    }

    private fun currentWeekDateKeys(): Set<String> {
        val day = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
        return buildSet {
            repeat(7) {
                add(formatDate(day))
                day.add(Calendar.DAY_OF_MONTH, 1)
            }
        }
    }

    private fun currentStreak(completedDays: Set<String>): Int {
        if (completedDays.isEmpty()) return 0
        val cursor = Calendar.getInstance()
        if (formatDate(cursor) !in completedDays) {
            cursor.add(Calendar.DAY_OF_MONTH, -1)
        }
        var streak = 0
        while (formatDate(cursor) in completedDays) {
            streak += 1
            cursor.add(Calendar.DAY_OF_MONTH, -1)
        }
        return streak
    }

    private fun formatDate(calendar: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
}
