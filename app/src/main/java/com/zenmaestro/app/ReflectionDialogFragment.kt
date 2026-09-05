package com.zenmaestro.app

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zenmaestro.app.databinding.DialogReflectionBinding

class ReflectionDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogReflectionBinding.inflate(layoutInflater)
        return MaterialAlertDialogBuilder(
            requireContext(),
            R.style.ThemeOverlay_ZenMaestro_MaterialAlertDialog
        )
            .setTitle(R.string.quick_reflection)
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_complete) { _, _ ->
                val effort = when (binding.effortGroup.checkedRadioButtonId) {
                    R.id.easierOption -> getString(R.string.easier_than_expected)
                    R.id.harderOption -> getString(R.string.harder_than_expected)
                    else -> getString(R.string.about_right)
                }
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(
                        RESULT_TASK_ID to requireArguments().getString(ARG_TASK_ID).orEmpty(),
                        RESULT_EFFORT to effort,
                        RESULT_NOTE to binding.reflectionNote.text?.toString()?.trim().orEmpty()
                    )
                )
            }
            .create()
    }

    companion object {
        const val REQUEST_KEY = "reflection_result"
        const val RESULT_TASK_ID = "reflection_task_id"
        const val RESULT_EFFORT = "reflection_effort"
        const val RESULT_NOTE = "reflection_note"
        private const val ARG_TASK_ID = "task_id"

        fun newInstance(taskId: String) = ReflectionDialogFragment().apply {
            arguments = bundleOf(ARG_TASK_ID to taskId)
        }
    }
}
