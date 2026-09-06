package com.zenmaestro.app

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zenmaestro.app.databinding.ActivityPlanBinding
import com.zenmaestro.app.databinding.BottomSheetAddTaskBinding
import com.zenmaestro.app.databinding.DialogAddTaskBinding
import com.zenmaestro.app.databinding.ItemPlanTaskBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

class PlanFragment : Fragment() {

    private var _binding: ActivityPlanBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: PlanStore
    private lateinit var plannerService: ZenPlannerService
    private var tasksChangedListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var tasks = mutableListOf<PlanTask>()
    private val weekDates by lazy { buildCurrentWeek() }
    private var selectedDayIndex = currentDayIndex()
    private var plannerRequestRunning = false

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val transcript = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (transcript.isNotBlank()) {
                binding.voiceTranscriptInput.setText(transcript)
                binding.voiceTranscriptLayout.visibility = View.VISIBLE
                binding.buildVoicePlanButton.visibility = View.VISIBLE
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityPlanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = PlanStore(requireContext())
        tasksChangedListener = store.registerOnTasksChanged {
            tasks = store.load()
            if (_binding != null) renderSelectedDay()
        }
        plannerService = ZenPlannerService()
        tasks = store.load()
        setupWeekSelector()
        setupInputModes()
        setupActions()
        renderSelectedDay()
    }

    override fun onResume() {
        super.onResume()
        tasks = store.load()
        if (_binding != null) renderSelectedDay()
    }

    private fun setupWeekSelector() {
        dayButtons().forEachIndexed { index, button ->
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            button.isCheckable = true
            button.setTextColor(ContextCompat.getColorStateList(requireContext(), R.color.plan_day_text))
            button.text = SimpleDateFormat("EEE\nd", Locale.ENGLISH)
                .format(weekDates[index].time)
                .uppercase(Locale.ENGLISH)
            button.setOnClickListener {
                selectedDayIndex = index
                updateSelectedDay()
                renderSelectedDay()
            }
        }
        updateSelectedDay()
        updateSelectedDateLabel()
    }

    private fun setupInputModes() {
        listOf(
            binding.modeZenButton,
            binding.modeVoiceButton,
            binding.modeManualButton,
        ).forEach { button ->
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        binding.planModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.modeZenButton -> showInputMode(InputMode.ZEN)
                R.id.modeVoiceButton -> showInputMode(InputMode.VOICE)
                R.id.modeManualButton -> showInputMode(InputMode.MANUAL)
            }
        }
        showInputMode(InputMode.ZEN)
    }

    private fun setupActions() {
        binding.planOptionsButton.setOnClickListener {
            if (binding.planModeGroup.checkedButtonId == R.id.modeManualButton) {
                showManualTaskSheet()
            } else {
                binding.planModeGroup.check(R.id.modeManualButton)
            }
        }
        binding.buildPlanButton.setOnClickListener {
            val raw = binding.zenPlanInput.text?.toString().orEmpty()
            buildDraftFrom(raw, binding.zenPlanInputLayout)
        }
        binding.recordVoiceButton.setOnClickListener { launchVoiceInput() }
        binding.buildVoicePlanButton.setOnClickListener {
            val raw = binding.voiceTranscriptInput.text?.toString().orEmpty()
            buildDraftFrom(raw, binding.voiceTranscriptLayout)
        }
        binding.addManualTaskButton.setOnClickListener { showManualTaskSheet() }
        binding.schedulePlanButton.setOnClickListener { scheduleSelectedDay() }
        binding.addBreakButton.setOnClickListener { addBreak() }
    }

    private fun showInputMode(mode: InputMode) {
        binding.zenInputPanel.visibility = if (mode == InputMode.ZEN) View.VISIBLE else View.GONE
        binding.voiceInputPanel.visibility = if (mode == InputMode.VOICE) View.VISIBLE else View.GONE
        binding.manualInputPanel.visibility = if (mode == InputMode.MANUAL) View.VISIBLE else View.GONE

        when (mode) {
            InputMode.ZEN -> {
                binding.composerTitle.setText(R.string.describe_learning_day)
                binding.composerMessage.setText(R.string.describe_learning_day_message)
            }
            InputMode.VOICE -> {
                binding.composerTitle.setText(R.string.say_learning_day)
                binding.composerMessage.setText(R.string.voice_review_message)
            }
            InputMode.MANUAL -> {
                binding.composerTitle.setText(R.string.add_clear_task)
                binding.composerMessage.setText(R.string.manual_task_message)
                showManualTaskSheet()
            }
        }
    }

    private fun buildDraftFrom(rawInput: String, inputLayout: com.google.android.material.textfield.TextInputLayout) {
        val cleaned = rawInput.trim()
        inputLayout.error = null
        if (cleaned.isBlank()) {
            inputLayout.error = getString(R.string.describe_day_error)
            return
        }

        if (plannerRequestRunning) return
        setPlannerRequestRunning(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                plannerService.createDraft(
                    rawInput = cleaned,
                    existingCategories = tasks.map { it.type }.distinct()
                )
            }
            if (_binding == null) return@launch
            setPlannerRequestRunning(false)
            result.onSuccess(::showAiDraftReview)
                .onFailure {
                    Toast.makeText(
                        requireContext(),
                        R.string.planner_ai_unavailable,
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun showAiDraftReview(response: ZenPlanDraft) {
        val selectedKey = selectedDateKey()
        val draft = response.tasks.map { task ->
            PlanTask(
                id = UUID.randomUUID().toString(),
                title = task.title,
                durationMinutes = task.durationMinutes,
                priority = task.priority,
                type = task.type,
                dateKey = selectedKey
            )
        }
        val taskSummary = draft.joinToString("\n") { task ->
            "• ${task.title} — ${formatDuration(task.durationMinutes)}"
        }
        val message = listOf(response.summary, taskSummary)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        MaterialAlertDialogBuilder(
            requireContext(),
            R.style.ThemeOverlay_ZenMaestro_MaterialAlertDialog
        )
            .setTitle(R.string.ai_draft_ready)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add_draft_to_plan) { _, _ -> addReviewedDraft(draft) }
            .show()
    }

    private fun addReviewedDraft(draft: List<PlanTask>) {
        val selectedKey = selectedDateKey()
        val existingMinutes = tasks.filter { it.dateKey == selectedKey }.sumOf { it.durationMinutes }
        val proposedMinutes = draft.sumOf { it.durationMinutes }
        if (draft.size > 3 || existingMinutes + proposedMinutes > CAPACITY_WARNING_MINUTES) {
            showCapacityDialog(draft, existingMinutes + proposedMinutes)
        } else {
            saveDraft(draft)
        }
    }

    private fun setPlannerRequestRunning(running: Boolean) {
        plannerRequestRunning = running
        binding.buildPlanButton.isEnabled = !running
        binding.buildVoicePlanButton.isEnabled = !running
        binding.recordVoiceButton.isEnabled = !running
    }

    private fun showCapacityDialog(draft: List<PlanTask>, totalMinutes: Int) {
        MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_ZenMaestro_MaterialAlertDialog)
            .setTitle(R.string.capacity_title)
            .setMessage(getString(R.string.capacity_message, formatDuration(totalMinutes)))
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.keep_all) { _, _ -> saveDraft(draft) }
            .setPositiveButton(R.string.keep_top_tasks) { _, _ ->
                saveWithinCapacity(draft)
                Toast.makeText(requireContext(), R.string.remaining_tasks_tomorrow, Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun saveWithinCapacity(draft: List<PlanTask>) {
        val selectedKey = selectedDateKey()
        val tomorrow = (weekDates[selectedDayIndex].clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, 1)
        }
        val tomorrowKey = dateKey(tomorrow)
        val selectedDayTasks = (tasks.filter { it.dateKey == selectedKey } + draft)
            .sortedBy { it.priority }

        var plannedMinutes = 0
        var plannedCount = 0
        val balancedTasks = selectedDayTasks.map { task ->
            val fitsToday = plannedCount < 3 &&
                (plannedCount == 0 || plannedMinutes + task.durationMinutes <= CAPACITY_WARNING_MINUTES)
            if (fitsToday) {
                plannedCount += 1
                plannedMinutes += task.durationMinutes
                task.copy(dateKey = selectedKey)
            } else {
                task.copy(
                    dateKey = tomorrowKey,
                    scheduledStart = null,
                    scheduledEnd = null
                )
            }
        }

        tasks = tasks.filterNot { it.dateKey == selectedKey }.toMutableList()
        tasks.addAll(balancedTasks)
        store.save(tasks)
        binding.zenPlanInput.text?.clear()
        binding.voiceTranscriptInput.text?.clear()
        renderSelectedDay()
        binding.planDraftNote.setText(R.string.plan_draft_note)
    }

    private fun saveDraft(draft: List<PlanTask>) {
        tasks.addAll(draft)
        store.save(tasks)
        binding.zenPlanInput.text?.clear()
        binding.voiceTranscriptInput.text?.clear()
        renderSelectedDay()
        binding.planDraftNote.setText(R.string.plan_draft_note)
    }

    private fun showManualTaskSheet() {
        if (!isAdded) return
        val sheetBinding = BottomSheetAddTaskBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(requireContext()).apply {
            setContentView(sheetBinding.root)
            setOnShowListener {
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                    sheet.setBackgroundColor(Color.TRANSPARENT)
                    sheet.layoutParams = sheet.layoutParams.apply {
                        height = (resources.displayMetrics.heightPixels * 0.86f).toInt()
                    }
                    BottomSheetBehavior.from(sheet).apply {
                        state = BottomSheetBehavior.STATE_EXPANDED
                        skipCollapsed = true
                    }
                }
            }
        }

        sheetBinding.closeButton.setOnClickListener { dialog.dismiss() }
        sheetBinding.addTaskButton.setOnClickListener {
            val title = sheetBinding.taskTitleInput.text?.toString()?.trim().orEmpty()
            sheetBinding.taskTitleLayout.error = null
            if (title.length < 2) {
                sheetBinding.taskTitleLayout.error = getString(R.string.task_title_error)
                sheetBinding.taskTitleInput.requestFocus()
                return@setOnClickListener
            }

            val duration = when (sheetBinding.durationGroup.checkedButtonId) {
                R.id.duration15Button -> 15
                R.id.duration45Button -> 45
                R.id.duration60Button -> 60
                else -> 30
            }
            val priority = when (sheetBinding.priorityGroup.checkedButtonId) {
                R.id.priorityHighButton -> 1
                R.id.priorityLowButton -> 5
                else -> 3
            }

            tasks += PlanTask(
                id = System.currentTimeMillis().toString(),
                title = title,
                durationMinutes = duration,
                priority = priority,
                type = getString(R.string.learning),
                dateKey = selectedDateKey()
            )
            store.save(tasks)
            dialog.dismiss()
            renderSelectedDay()
            binding.planDraftNote.setText(R.string.manual_task_added_note)
        }
        dialog.show()
    }

    private fun launchVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.tap_describe_day))
        }
        runCatching { voiceLauncher.launch(intent) }
            .onFailure {
                Toast.makeText(requireContext(), R.string.voice_unavailable, Toast.LENGTH_SHORT).show()
            }
    }

    private fun addBreak() {
        tasks += PlanTask(
            id = System.currentTimeMillis().toString(),
            title = getString(R.string.break_label),
            durationMinutes = 15,
            priority = 5,
            type = getString(R.string.recovery),
            dateKey = selectedDateKey(),
            isBreak = true
        )
        store.save(tasks)
        renderSelectedDay()
        binding.planDraftNote.setText(R.string.break_added_note)
    }

    private fun scheduleSelectedDay() {
        val dayTasks = tasks.filter { it.dateKey == selectedDateKey() }
        if (dayTasks.isEmpty()) return

        var cursorMinutes = 9 * 60
        dayTasks.forEach { task ->
            val start = minutesToStoredTime(cursorMinutes)
            cursorMinutes += task.durationMinutes
            val end = minutesToStoredTime(cursorMinutes)
            replaceTask(task.copy(scheduledStart = start, scheduledEnd = end), saveImmediately = false)
            if (!task.isBreak) cursorMinutes += 10
        }
        store.save(tasks)
        renderSelectedDay()
        binding.planDraftNote.setText(R.string.schedule_ready_note)
    }

    private fun renderSelectedDay() {
        updateSelectedDateLabel()
        val selectedKey = selectedDateKey()
        val dayTasks = tasks.filter { it.dateKey == selectedKey }
        val isToday = selectedKey == todayKey()
        val scheduled = dayTasks.isNotEmpty() && dayTasks.all { !it.scheduledStart.isNullOrBlank() }

        binding.dayPlanTitle.text = if (isToday) {
            getString(R.string.todays_plan_title)
        } else {
            getString(
                R.string.named_day_plan,
                SimpleDateFormat("EEEE", Locale.ENGLISH).format(weekDates[selectedDayIndex].time)
            )
        }
        binding.dayPlanStatus.text = when {
            dayTasks.isEmpty() -> getString(R.string.waiting_input)
            scheduled -> getString(R.string.task_count_scheduled, dayTasks.size)
            else -> getString(R.string.task_count_review, dayTasks.size)
        }
        binding.planDraftNote.setText(
            if (scheduled) R.string.schedule_ready_note else R.string.plan_draft_note
        )

        binding.emptyStateCard.visibility = if (dayTasks.isEmpty()) View.VISIBLE else View.GONE
        binding.taskListContainer.visibility = if (dayTasks.isEmpty()) View.GONE else View.VISIBLE
        binding.planActionRow.visibility = if (dayTasks.isEmpty()) View.GONE else View.VISIBLE
        binding.planDraftNote.visibility = if (dayTasks.isEmpty()) View.GONE else View.VISIBLE
        binding.taskListContainer.removeAllViews()

        dayTasks.forEach { task ->
            val itemBinding = ItemPlanTaskBinding.inflate(
                LayoutInflater.from(requireContext()),
                binding.taskListContainer,
                false
            )
            bindTask(itemBinding, task)
            binding.taskListContainer.addView(itemBinding.root)
        }
    }

    private fun bindTask(itemBinding: ItemPlanTaskBinding, task: PlanTask) {
        itemBinding.taskTitle.text = task.title
        itemBinding.taskMeta.text = if (task.isBreak) {
            getString(R.string.break_meta, task.durationMinutes)
        } else {
            getString(R.string.plan_task_meta, task.priority, task.durationMinutes)
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
            replaceTask(task.copy(completed = checked))
        }
        itemBinding.startTaskButton.visibility = if (task.completed || task.isBreak) View.GONE else View.VISIBLE
        itemBinding.startTaskButton.setOnClickListener {
            (requireActivity() as AppNavigator).openFocus(task.id)
        }
        itemBinding.taskRow.setOnClickListener { showEditTaskDialog(task) }
        itemBinding.taskMenuButton.setOnClickListener { showTaskMenu(task) }
    }

    private fun applyCompletedStyle(itemBinding: ItemPlanTaskBinding, completed: Boolean) {
        itemBinding.taskTitle.paintFlags = if (completed) {
            itemBinding.taskTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            itemBinding.taskTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        itemBinding.taskTitle.alpha = if (completed) 0.55f else 1f
        itemBinding.taskMeta.alpha = if (completed) 0.55f else 1f
    }

    private fun showTaskMenu(task: PlanTask) {
        MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_ZenMaestro_MaterialAlertDialog)
            .setItems(arrayOf(getString(R.string.edit_task), getString(R.string.remove))) { _, which ->
                if (which == 0) showEditTaskDialog(task) else confirmDelete(task)
            }
            .show()
    }

    private fun showEditTaskDialog(task: PlanTask) {
        val dialogBinding = DialogAddTaskBinding.inflate(LayoutInflater.from(requireContext()))
        val typeOptions = resources.getStringArray(R.array.plan_task_types)
        val dayOptions = weekDates.map {
            SimpleDateFormat("EEE, MMM d", Locale.ENGLISH).format(it.time)
        }
        dialogBinding.taskTypeInput.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, typeOptions)
        )
        dialogBinding.taskDayInput.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, dayOptions)
        )
        val dayIndex = weekDates.indexOfFirst { dateKey(it) == task.dateKey }.takeIf { it >= 0 }
            ?: selectedDayIndex
        dialogBinding.taskTitleInput.setText(task.title)
        dialogBinding.taskDurationInput.setText(task.durationMinutes.toString())
        dialogBinding.taskTypeInput.setText(task.type, false)
        dialogBinding.taskDayInput.setText(dayOptions[dayIndex], false)

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_ZenMaestro_MaterialAlertDialog)
            .setTitle(R.string.edit_task)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val title = dialogBinding.taskTitleInput.text?.toString()?.trim().orEmpty()
                val duration = dialogBinding.taskDurationInput.text?.toString()?.toIntOrNull()
                val chosenDay = dayOptions.indexOf(dialogBinding.taskDayInput.text?.toString().orEmpty())
                dialogBinding.taskTitleLayout.error = null
                dialogBinding.taskDurationLayout.error = null
                dialogBinding.taskDayLayout.error = null
                when {
                    title.length < 2 -> dialogBinding.taskTitleLayout.error = getString(R.string.task_title_error)
                    duration == null || duration !in 5..480 -> {
                        dialogBinding.taskDurationLayout.error = getString(R.string.task_duration_error)
                    }
                    chosenDay < 0 -> dialogBinding.taskDayLayout.error = getString(R.string.task_day_error)
                    else -> {
                        replaceTask(
                            task.copy(
                                title = title,
                                durationMinutes = duration,
                                type = dialogBinding.taskTypeInput.text?.toString()?.ifBlank { task.type } ?: task.type,
                                dateKey = dateKey(weekDates[chosenDay]),
                                scheduledStart = null,
                                scheduledEnd = null
                            )
                        )
                        selectedDayIndex = chosenDay
                        updateSelectedDay()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(task: PlanTask) {
        MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_ZenMaestro_MaterialAlertDialog)
            .setTitle(R.string.remove_task_title)
            .setMessage(getString(R.string.remove_task_message, task.title))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ ->
                tasks.removeAll { it.id == task.id }
                store.save(tasks)
                renderSelectedDay()
            }
            .show()
    }

    private fun replaceTask(updated: PlanTask, saveImmediately: Boolean = true) {
        val index = tasks.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            tasks[index] = updated
            if (saveImmediately) store.save(tasks)
            renderSelectedDay()
        }
    }

    private fun updateSelectedDay() {
        dayButtons().forEachIndexed { index, button ->
            button.isChecked = index == selectedDayIndex
        }
    }

    private fun updateSelectedDateLabel() {
        binding.selectedDateLabel.text = SimpleDateFormat(
            "EEEE, MMMM d",
            Locale.ENGLISH
        ).format(weekDates[selectedDayIndex].time)
    }

    private fun buildCurrentWeek(): List<Calendar> {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -currentDayIndex())
        }
        return List(7) { index ->
            (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, index) }
        }
    }

    private fun currentDayIndex(): Int = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7

    private fun selectedDateKey(): String = dateKey(weekDates[selectedDayIndex])

    private fun todayKey(): String = dateKey(Calendar.getInstance())

    private fun dateKey(calendar: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)

    private fun dayButtons() = listOf(
        binding.dayMonday,
        binding.dayTuesday,
        binding.dayWednesday,
        binding.dayThursday,
        binding.dayFriday,
        binding.daySaturday,
        binding.daySunday
    )

    private fun priorityFromLabel(label: String): Int = when {
        label.startsWith("High") -> 1
        label.startsWith("Low") -> 5
        else -> 3
    }

    private fun formatDuration(minutes: Int): String = when {
        minutes < 60 -> getString(R.string.duration_minutes, minutes)
        minutes % 60 == 0 -> getString(R.string.duration_hours, minutes / 60)
        else -> getString(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
    }

    private fun minutesToStoredTime(minutes: Int): String =
        String.format(Locale.US, "%02d:%02d", (minutes / 60) % 24, minutes % 60)

    private fun formatStoredTime(value: String): String = runCatching {
        val parsed = SimpleDateFormat("HH:mm", Locale.US).parse(value) ?: return value
        SimpleDateFormat("h:mm a", Locale.ENGLISH).format(parsed)
    }.getOrDefault(value)

    override fun onDestroyView() {
        store.unregisterOnTasksChanged(tasksChangedListener)
        tasksChangedListener = null
        _binding = null
        super.onDestroyView()
    }

    private enum class InputMode { ZEN, VOICE, MANUAL }

    companion object {
        private const val CAPACITY_WARNING_MINUTES = 150
    }
}
