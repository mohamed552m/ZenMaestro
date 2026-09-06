package com.zenmaestro.app

import android.content.SharedPreferences
import android.Manifest
import android.app.TimePickerDialog
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.zenmaestro.app.databinding.DialogEditProfileBinding
import com.zenmaestro.app.databinding.FragmentProfileBinding
import java.util.Calendar

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: PlanStore
    private var tasksChangedListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private lateinit var reflectionStore: ReflectionStore
    private val preferences by lazy {
        requireContext().getSharedPreferences(NotificationCoordinator.PROFILE_PREFERENCES, 0)
    }
    private var updatingReminderSwitch = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!isAdded) return@registerForActivityResult
        NotificationCoordinator.setRemindersEnabled(requireContext(), granted)
        updateReminderSwitch(granted)
        if (granted) {
            NotificationCoordinator.sync(requireContext(), store.load())
        } else {
            Toast.makeText(
                requireContext(),
                R.string.notification_permission_denied,
                Toast.LENGTH_LONG
            ).show()
        }
        syncAccountPreferences()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = PlanStore(requireContext())
        tasksChangedListener = store.registerOnTasksChanged {
            if (_binding != null) renderStats()
        }
        reflectionStore = ReflectionStore(requireContext())
        renderAccount()
        renderLearningStart()
        configureReminders()
        syncAccountPreferences()
        binding.learningStartRow.setOnClickListener { showLearningStartPicker() }
        binding.editProfileButton.setOnClickListener { showEditProfile() }
        binding.resetPasswordRow.setOnClickListener { handleSignInManagement() }
        binding.privacyRow.setOnClickListener { showDataPrivacy() }
        binding.signOutButton.setOnClickListener { confirmSignOut() }
        val versionName = runCatching {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0)
                .versionName
        }.getOrNull().orEmpty()
        binding.appVersionText.text = getString(R.string.app_version, versionName)
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) renderStats()
    }

    private fun renderAccount() {
        val user = FirebaseAuth.getInstance().currentUser
        val fallbackName = user?.email?.substringBefore('@')?.replaceFirstChar { it.uppercase() }
        val name = user?.displayName?.takeIf { it.isNotBlank() }
            ?: fallbackName
            ?: getString(R.string.learner)
        binding.profileName.text = name
        binding.profileEmail.text = user?.email ?: getString(R.string.signed_in_account)
        binding.avatarText.text = initials(name)

        val verified = user?.isEmailVerified == true
        binding.profileVerificationText.setText(
            if (verified) R.string.verified_account else R.string.email_not_verified
        )
        if (verified) {
            binding.profileVerificationIcon.setImageResource(R.drawable.ic_check_circle)
            ImageViewCompat.setImageTintList(binding.profileVerificationIcon, null)
            binding.profileVerificationText.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.sage_green)
            )
        } else {
            binding.profileVerificationIcon.setImageResource(R.drawable.ic_shield)
            ImageViewCompat.setImageTintList(
                binding.profileVerificationIcon,
                ContextCompat.getColorStateList(requireContext(), R.color.sage)
            )
            binding.profileVerificationText.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.sage)
            )
        }

        val providerIds = user?.providerData?.map { it.providerId }.orEmpty()
        binding.passwordSignInMessage.text = when {
            EmailAuthProvider.PROVIDER_ID in providerIds -> getString(R.string.change_password_message)
            GoogleAuthProvider.PROVIDER_ID in providerIds -> getString(R.string.signed_in_with_google)
            else -> getString(R.string.signed_in_account)
        }
    }

    private fun initials(name: String): String {
        return name.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .joinToString("")
            .ifBlank { "L" }
    }

    private fun renderStats() {
        val metrics = LearningMetricsCalculator.calculate(store.load())
        binding.profileCompletedValue.text = metrics.completedTasks.size.toString()
        binding.profileFocusedValue.text = formatMinutes(metrics.focusedMinutes)
        binding.profileStreakValue.text = metrics.streakDays.toString()
    }

    private fun configureReminders() {
        val enabled = NotificationCoordinator.remindersEnabled(requireContext()) &&
            NotificationCoordinator.hasPermission(requireContext())
        updateReminderSwitch(enabled)
        binding.remindersSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingReminderSwitch) return@setOnCheckedChangeListener
            if (checked && !NotificationCoordinator.hasPermission(requireContext())) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                return@setOnCheckedChangeListener
            }
            NotificationCoordinator.setRemindersEnabled(requireContext(), checked)
            if (checked) NotificationCoordinator.sync(requireContext(), store.load())
            syncAccountPreferences()
        }
    }

    private fun updateReminderSwitch(checked: Boolean) {
        if (_binding == null) return
        updatingReminderSwitch = true
        binding.remindersSwitch.isChecked = checked
        updatingReminderSwitch = false
    }

    private fun renderLearningStart() {
        val hour = preferences.getInt(KEY_START_HOUR, DEFAULT_START_HOUR)
        val minute = preferences.getInt(KEY_START_MINUTE, 0)
        binding.learningStartValue.text = formatTime(hour, minute)
    }

    private fun showLearningStartPicker() {
        val hour = preferences.getInt(KEY_START_HOUR, DEFAULT_START_HOUR)
        val minute = preferences.getInt(KEY_START_MINUTE, 0)
        TimePickerDialog(
            requireContext(),
            { _, selectedHour, selectedMinute ->
                preferences.edit()
                    .putInt(KEY_START_HOUR, selectedHour)
                    .putInt(KEY_START_MINUTE, selectedMinute)
                    .apply()
                binding.learningStartValue.text = formatTime(selectedHour, selectedMinute)
                syncAccountPreferences()
            },
            hour,
            minute,
            DateFormat.is24HourFormat(requireContext())
        ).show()
    }

    private fun showDataPrivacy() {
        val taskCount = store.load().count { !it.isBreak }
        val reflectionCount = reflectionStore.load().size
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.your_learning_data)
            .setMessage(getString(R.string.local_data_summary, taskCount, reflectionCount))
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.clear_learning_data) { _, _ -> confirmClearData() }
            .show()
    }

    private fun showEditProfile() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Toast.makeText(requireContext(), R.string.profile_update_failed, Toast.LENGTH_SHORT)
                .show()
            return
        }

        val dialogBinding = DialogEditProfileBinding.inflate(layoutInflater)
        val currentName = user.displayName?.takeIf { it.isNotBlank() }
            ?: user.email?.substringBefore('@')?.replaceFirstChar { it.uppercase() }
            ?: getString(R.string.learner)
        dialogBinding.displayNameInput.setText(currentName)
        dialogBinding.displayNameInput.setSelection(currentName.length)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit_profile)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val displayName = dialogBinding.displayNameInput.text
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (displayName.isBlank()) {
                    dialogBinding.displayNameInputLayout.error =
                        getString(R.string.display_name_required)
                    return@setOnClickListener
                }

                dialogBinding.displayNameInputLayout.error = null
                saveButton.isEnabled = false
                val request = UserProfileChangeRequest.Builder()
                    .setDisplayName(displayName)
                    .build()
                user.updateProfile(request).addOnCompleteListener { task ->
                    if (!isAdded) return@addOnCompleteListener
                    saveButton.isEnabled = true
                    if (task.isSuccessful) {
                        renderAccount()
                        Toast.makeText(
                            requireContext(),
                            R.string.profile_updated,
                            Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                    } else {
                        dialogBinding.displayNameInputLayout.error =
                            getString(R.string.profile_update_failed)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun handleSignInManagement() {
        val providerIds = FirebaseAuth.getInstance().currentUser
            ?.providerData
            ?.map { it.providerId }
            .orEmpty()
        when {
            EmailAuthProvider.PROVIDER_ID in providerIds -> confirmPasswordReset()
            GoogleAuthProvider.PROVIDER_ID in providerIds -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.sign_in_provider_title)
                    .setMessage(R.string.google_sign_in_message)
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
            else -> confirmPasswordReset()
        }
    }

    private fun confirmPasswordReset() {
        val email = FirebaseAuth.getInstance().currentUser?.email
        if (email.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.reset_email_failed_message, Toast.LENGTH_SHORT)
                .show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.send_password_reset_question)
            .setMessage(getString(R.string.send_password_reset_profile_message, email))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.send_reset_link) { _, _ ->
                FirebaseAuth.getInstance().sendPasswordResetEmail(email)
                    .addOnCompleteListener { task ->
                        if (!isAdded) return@addOnCompleteListener
                        Toast.makeText(
                            requireContext(),
                            if (task.isSuccessful) R.string.password_reset_sent
                            else R.string.reset_email_failed_message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
            .show()
    }

    private fun confirmClearData() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clear_learning_data_title)
            .setMessage(R.string.clear_learning_data_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear) { _, _ ->
                store.clear()
                reflectionStore.clear()
                AccountSyncCoordinator.deleteLearningData(requireContext())
                renderStats()
                Toast.makeText(requireContext(), R.string.learning_data_cleared, Toast.LENGTH_SHORT)
                    .show()
            }
            .show()
    }

    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sign_out_question)
            .setMessage(R.string.sign_out_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.sign_out) { _, _ ->
                (requireActivity() as AppNavigator).signOut()
            }
            .show()
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return DateFormat.getTimeFormat(requireContext()).format(calendar.time)
    }

    private fun syncAccountPreferences() {
        AccountSyncCoordinator.syncPreferences(
            context = requireContext(),
            learningStartHour = preferences.getInt(KEY_START_HOUR, DEFAULT_START_HOUR),
            learningStartMinute = preferences.getInt(KEY_START_MINUTE, 0),
            remindersEnabled = NotificationCoordinator.remindersEnabled(requireContext())
        )
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

    companion object {
        private const val KEY_START_HOUR = "learning_start_hour"
        private const val KEY_START_MINUTE = "learning_start_minute"
        private const val DEFAULT_START_HOUR = 9
    }
}
