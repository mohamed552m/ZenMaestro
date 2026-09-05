package com.zenmaestro.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zenmaestro.app.databinding.FragmentFocusBinding

class FocusFragment : Fragment() {

    private var _binding: FragmentFocusBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: PlanStore
    private var task: PlanTask? = null
    private var remainingSeconds = 0
    private var timerRunning = true
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (timerRunning && remainingSeconds > 0) {
                remainingSeconds -= 1
                renderTimer()
            }
            if (remainingSeconds == 0) {
                timerRunning = false
                if (_binding != null) binding.timerCaption.setText(R.string.focus_time_complete)
                return
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFocusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = PlanStore(requireContext())
        task = store.load().firstOrNull { it.id == requireArguments().getString(ARG_TASK_ID) }
        remainingSeconds = savedInstanceState?.getInt(STATE_REMAINING_SECONDS)
            ?: ((task?.durationMinutes ?: 0) * 60)
        timerRunning = savedInstanceState?.getBoolean(STATE_TIMER_RUNNING) ?: true

        binding.taskTitle.text = task?.title ?: getString(R.string.task_not_found)
        binding.pauseButton.isEnabled = task != null
        binding.finishButton.isEnabled = task != null
        binding.couldNotFinishButton.isEnabled = task != null
        binding.backButton.setOnClickListener { (requireActivity() as AppNavigator).closeFocus() }
        binding.pauseButton.setOnClickListener { togglePause() }
        binding.finishButton.setOnClickListener {
            task?.let { ReflectionDialogFragment.newInstance(it.id).show(parentFragmentManager, "reflection") }
        }
        binding.couldNotFinishButton.setOnClickListener { confirmKeepPending() }
        parentFragmentManager.setFragmentResultListener(
            ReflectionDialogFragment.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, result -> completeTask(result) }
        renderTimer()
        renderTimerState()
    }

    override fun onStart() {
        super.onStart()
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_REMAINING_SECONDS, remainingSeconds)
        outState.putBoolean(STATE_TIMER_RUNNING, timerRunning)
        super.onSaveInstanceState(outState)
    }

    private fun togglePause() {
        timerRunning = !timerRunning
        renderTimerState()
    }

    private fun renderTimer() {
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        binding.timerValue.text = String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun renderTimerState() {
        binding.pauseButton.setText(if (timerRunning) R.string.pause else R.string.resume)
        binding.timerCaption.setText(if (timerRunning) R.string.focus_in_progress else R.string.focus_paused)
    }

    private fun completeTask(result: Bundle) {
        val taskId = result.getString(ReflectionDialogFragment.RESULT_TASK_ID).orEmpty()
        val tasks = store.load()
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index >= 0) {
            tasks[index] = tasks[index].copy(completed = true)
            store.save(tasks)
            ReflectionStore(requireContext()).add(
                LearningReflection(
                    taskId = taskId,
                    effort = result.getString(ReflectionDialogFragment.RESULT_EFFORT).orEmpty(),
                    note = result.getString(ReflectionDialogFragment.RESULT_NOTE).orEmpty(),
                    completedAt = System.currentTimeMillis()
                )
            )
        }
        (requireActivity() as AppNavigator).showMainTab(MainTab.HOME)
    }

    private fun confirmKeepPending() {
        MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_ZenMaestro_MaterialAlertDialog)
            .setTitle(R.string.could_not_finish)
            .setMessage(R.string.keep_pending)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.keep_pending) { _, _ ->
                (requireActivity() as AppNavigator).showMainTab(MainTab.HOME)
            }
            .show()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(tick)
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TASK_ID = "task_id"
        private const val STATE_REMAINING_SECONDS = "remaining_seconds"
        private const val STATE_TIMER_RUNNING = "timer_running"

        fun newInstance(taskId: String) = FocusFragment().apply {
            arguments = bundleOf(ARG_TASK_ID to taskId)
        }
    }
}
