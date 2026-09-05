package com.zenmaestro.app

import android.Manifest
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zenmaestro.app.databinding.ActivityHomeBinding
import com.zenmaestro.app.databinding.ItemHomeTaskBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HomeFragment : Fragment() {

    private var _binding: ActivityHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: PlanStore

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            NotificationCoordinator.setRemindersEnabled(requireContext(), true)
            NotificationCoordinator.sync(requireContext(), store.load())
            showReminderStatus()
        } else {
            Toast.makeText(requireContext(), R.string.notification_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = PlanStore(requireContext())
        setupActions()
    }

    override fun onResume() {
        super.onResume()
        renderHome()
    }

    private fun setupActions() {
        binding.notificationButton.setOnClickListener { openNotifications() }
        binding.streakCard.setOnClickListener {
            Toast.makeText(requireContext(), R.string.streak_from_completed_days, Toast.LENGTH_SHORT).show()
        }
        binding.openPlanButton.setOnClickListener { openPlan() }
        binding.emptyOpenPlanButton.setOnClickListener { openPlan() }
        binding.mainActionButton.setOnClickListener {
            val learningTasks = todayTasks().filterNot { it.isBreak }
            val pendingTask = learningTasks.firstOrNull { !it.completed }
            when {
                learningTasks.isEmpty() -> openPlan()
                pendingTask != null -> openFocus(pendingTask.id)
                else -> (requireActivity() as AppNavigator).showMainTab(MainTab.PROGRESS)
            }
        }
    }

    private fun renderHome() {
        binding.greetingDate.text = getString(
            R.string.greeting_date_format,
            greeting(),
            SimpleDateFormat("EEE, MMM d", Locale.ENGLISH).format(Calendar.getInstance().time)
        )

        val allTasks = store.load()
        val metrics = LearningMetricsCalculator.calculate(allTasks)
        val dayTasks = allTasks.filter { it.dateKey == todayKey() }
        val learningTasks = dayTasks.filterNot { it.isBreak }
        val completedCount = learningTasks.count { it.completed }
        val progress = if (learningTasks.isEmpty()) 0 else completedCount * 100 / learningTasks.size
        val pendingTask = learningTasks.firstOrNull { !it.completed }

        binding.homeTaskCount.text = learningTasks.size.toString()
        binding.homePlannedTime.text = formatCompactMinutes(dayTasks.sumOf { it.durationMinutes })
        binding.homeCompletion.text = getString(R.string.progress_percent, progress)
        binding.streakValue.text = metrics.streakDays.toString()

        when {
            learningTasks.isEmpty() -> {
                binding.focusTitle.setText(R.string.home_empty_focus_title)
                binding.focusDescription.setText(R.string.home_empty_focus_message)
                binding.mainActionButton.setText(R.string.build_today_plan)
            }
            pendingTask != null -> {
                binding.focusTitle.text = pendingTask.title
                binding.focusDescription.text = pendingTask.scheduledStart?.let { start ->
                    getString(
                        R.string.home_scheduled_focus,
                        formatStoredTime(start),
                        pendingTask.durationMinutes
                    )
                } ?: getString(R.string.home_next_focus, pendingTask.durationMinutes)
                binding.mainActionButton.setText(R.string.start_focus)
            }
            else -> {
                binding.focusTitle.setText(R.string.home_plan_complete)
                binding.focusDescription.setText(R.string.home_plan_complete_message)
                binding.mainActionButton.setText(R.string.view_progress)
            }
        }

        binding.homeEmptyState.visibility = if (dayTasks.isEmpty()) View.VISIBLE else View.GONE
        binding.homeTaskList.visibility = if (dayTasks.isEmpty()) View.GONE else View.VISIBLE
        binding.homeTaskList.removeAllViews()
        dayTasks.take(3).forEach { task ->
            val itemBinding = ItemHomeTaskBinding.inflate(
                LayoutInflater.from(requireContext()),
                binding.homeTaskList,
                false
            )
            bindHomeTask(itemBinding, task, allTasks)
            binding.homeTaskList.addView(itemBinding.root)
        }

        binding.consistencyStatus.setText(
            if (metrics.streakDays > 0) R.string.learning_consistency_active else R.string.start_one_day
        )
        updateConsistencyDots(allTasks)
    }

    private fun bindHomeTask(
        itemBinding: ItemHomeTaskBinding,
        task: PlanTask,
        allTasks: MutableList<PlanTask>
    ) {
        itemBinding.taskTitle.text = task.title
        itemBinding.taskMeta.text = if (task.isBreak) {
            getString(R.string.break_meta, task.durationMinutes)
        } else {
            getString(R.string.home_task_meta, task.priority, task.durationMinutes)
        }
        itemBinding.taskTime.text = task.scheduledStart?.let { start ->
            task.scheduledEnd?.let { end ->
                getString(R.string.task_time_range, formatStoredTime(start), formatStoredTime(end))
            }
        } ?: getString(R.string.duration_minutes, task.durationMinutes)

        itemBinding.taskCheck.setOnCheckedChangeListener(null)
        itemBinding.taskCheck.isChecked = task.completed
        applyCompletedStyle(itemBinding, task.completed)
        itemBinding.taskCheck.setOnCheckedChangeListener { _, checked ->
            val index = allTasks.indexOfFirst { it.id == task.id }
            if (index >= 0) {
                allTasks[index] = task.copy(completed = checked)
                store.save(allTasks)
                renderHome()
            }
        }
        itemBinding.taskStartButton.visibility = if (task.completed || task.isBreak) View.GONE else View.VISIBLE
        itemBinding.taskStartButton.setOnClickListener { openFocus(task.id) }
    }

    private fun applyCompletedStyle(itemBinding: ItemHomeTaskBinding, completed: Boolean) {
        itemBinding.taskTitle.paintFlags = if (completed) {
            itemBinding.taskTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            itemBinding.taskTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        itemBinding.taskTitle.alpha = if (completed) 0.55f else 1f
        itemBinding.taskMeta.alpha = if (completed) 0.55f else 1f
    }

    private fun updateConsistencyDots(tasks: List<PlanTask>) {
        val dots = listOf(
            binding.dotMonday,
            binding.dotTuesday,
            binding.dotWednesday,
            binding.dotThursday,
            binding.dotFriday,
            binding.dotSaturday,
            binding.dotSunday
        )
        val completedDays = tasks.asSequence()
            .filter { it.completed && !it.isBreak }
            .map { it.dateKey }
            .toSet()
        val day = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
        dots.forEach { dot ->
            val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(day.time)
            styleDot(dot, key in completedDays)
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    private fun styleDot(dot: MaterialCardView, active: Boolean) {
        dot.setCardBackgroundColor(
            ContextCompat.getColor(requireContext(), if (active) R.color.sage_green else R.color.chalk_white)
        )
        dot.strokeColor = ContextCompat.getColor(
            requireContext(),
            if (active) R.color.sage_green else R.color.sage_tint
        )
        dot.strokeWidth = resources.getDimensionPixelSize(
            if (active) R.dimen.consistency_dot_active_stroke else R.dimen.consistency_dot_stroke
        )
    }

    private fun todayTasks(): List<PlanTask> = store.load().filter { it.dateKey == todayKey() }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)

    private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> getString(R.string.good_morning)
        in 12..16 -> getString(R.string.good_afternoon)
        else -> getString(R.string.good_evening)
    }

    private fun formatCompactMinutes(minutes: Int): String = when {
        minutes <= 0 -> "0m"
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }

    private fun formatStoredTime(value: String): String = runCatching {
        val parsed = SimpleDateFormat("HH:mm", Locale.US).parse(value) ?: return value
        SimpleDateFormat("h:mm a", Locale.ENGLISH).format(parsed)
    }.getOrDefault(value)

    private fun openPlan() {
        (requireActivity() as AppNavigator).showMainTab(MainTab.PLAN)
    }

    private fun openFocus(taskId: String) {
        (requireActivity() as AppNavigator).openFocus(taskId)
    }

    private fun openNotifications() {
        NotificationCoordinator.createChannel(requireContext())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationCoordinator.hasPermission(requireContext())
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showReminderStatus()
        }
    }

    private fun showReminderStatus() {
        if (!NotificationCoordinator.remindersEnabled(requireContext())) {
            MaterialAlertDialogBuilder(
                requireContext(),
                R.style.ThemeOverlay_ZenMaestro_MaterialAlertDialog
            ).setTitle(R.string.learning_reminders)
                .setMessage(R.string.reminders_are_off)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.turn_on_reminders) { _, _ ->
                    NotificationCoordinator.setRemindersEnabled(requireContext(), true)
                    NotificationCoordinator.sync(requireContext(), store.load())
                    showReminderStatus()
                }
                .show()
            return
        }
        val next = NotificationCoordinator.nextScheduledTask(store.load())
        val builder = MaterialAlertDialogBuilder(
            requireContext(),
            R.style.ThemeOverlay_ZenMaestro_MaterialAlertDialog
        ).setTitle(R.string.learning_reminders)
            .setMessage(
                next?.let { (task, time) ->
                    getString(
                        R.string.next_reminder_message,
                        task.title,
                        NotificationCoordinator.formatReminderTime(time)
                    )
                } ?: getString(R.string.no_scheduled_reminders)
            )
            .setNegativeButton(R.string.close, null)

        if (next == null) {
            builder.setPositiveButton(R.string.open_plan) { _, _ -> openPlan() }
        } else {
            builder.setNeutralButton(R.string.open_plan) { _, _ -> openPlan() }
            builder.setPositiveButton(R.string.send_test_reminder) { _, _ ->
                val task = next.first
                NotificationCoordinator.showTaskNotification(
                    requireContext(),
                    "test_${task.id}",
                    task.title,
                    task.durationMinutes
                )
            }
        }
        builder.show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
