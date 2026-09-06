package com.zenmaestro.app

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.zenmaestro.app.databinding.FragmentProgressBinding

class ProgressFragment : Fragment() {

    private var _binding: FragmentProgressBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: PlanStore
    private var tasksChangedListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProgressBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = PlanStore(requireContext())
        tasksChangedListener = store.registerOnTasksChanged {
            if (_binding != null) renderProgress()
        }
    }

    override fun onResume() {
        super.onResume()
        renderProgress()
    }

    private fun renderProgress() {
        val metrics = LearningMetricsCalculator.calculate(store.load())
        val percent = metrics.weekCompletionPercent

        binding.completionValue.text = getString(R.string.progress_percent, percent)
        binding.completedCount.text = metrics.completedTasks.size.toString()
        binding.focusedValue.text = formatMinutes(metrics.focusedMinutes)
        binding.streakValue.text = metrics.streakDays.toString()
        binding.weekProgress.setProgressCompat(percent, true)
        binding.weekProgressText.text = getString(
            R.string.week_progress_format,
            metrics.completedWeekTasks.size,
            metrics.weekTasks.size
        )

        if (metrics.weekTasks.isNotEmpty()) {
            binding.progressTitle.text = getString(R.string.progress_active_title, percent)
            binding.progressMessage.text = getString(
                R.string.progress_active_message,
                metrics.completedWeekTasks.size,
                metrics.weekTasks.size
            )
        } else {
            binding.progressTitle.setText(
                if (metrics.allTasks.isEmpty()) R.string.progress_starts_here
                else R.string.no_tasks_this_week_title
            )
            binding.progressMessage.setText(
                if (metrics.allTasks.isEmpty()) R.string.progress_empty_message
                else R.string.no_tasks_this_week_message
            )
        }

        renderInsight(metrics)
    }

    private fun renderInsight(metrics: LearningMetrics) {
        when {
            metrics.allTasks.isEmpty() -> {
                binding.insightTitle.setText(R.string.not_enough_history)
                binding.insightMessage.setText(R.string.not_enough_history_message)
            }
            metrics.completedTasks.isEmpty() -> {
                binding.insightTitle.setText(R.string.first_step_title)
                binding.insightMessage.setText(R.string.first_step_message)
            }
            metrics.streakDays >= 3 -> {
                binding.insightTitle.setText(R.string.consistency_taking_shape)
                binding.insightMessage.text = getString(
                    R.string.consistency_taking_shape_message,
                    metrics.streakDays
                )
            }
            metrics.weekTasks.isNotEmpty() &&
                metrics.completedWeekTasks.size == metrics.weekTasks.size -> {
                binding.insightTitle.setText(R.string.week_complete_title)
                binding.insightMessage.setText(R.string.week_complete_message)
            }
            else -> {
                val remaining = metrics.weekTasks.count { !it.completed }
                binding.insightTitle.setText(R.string.keep_plan_realistic_title)
                binding.insightMessage.text = getString(R.string.keep_plan_realistic_message, remaining)
            }
        }
    }

    private fun formatMinutes(minutes: Int): String = when {
        minutes <= 0 -> "0m"
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }

    override fun onDestroyView() {
        store.unregisterOnTasksChanged(tasksChangedListener)
        tasksChangedListener = null
        _binding = null
        super.onDestroyView()
    }
}
