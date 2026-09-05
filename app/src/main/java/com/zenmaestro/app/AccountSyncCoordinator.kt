package com.zenmaestro.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import java.util.TimeZone

object AccountSyncCoordinator {

    fun syncPreferences(
        context: Context,
        learningStartHour: Int,
        learningStartMinute: Int,
        remindersEnabled: Boolean
    ) {
        val userUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (BuildConfig.API_BASE_URL.isBlank()) return
        val input = Data.Builder()
            .putString(AccountPreferencesSyncWorker.KEY_USER_UID, userUid)
            .putInt(AccountPreferencesSyncWorker.KEY_START_HOUR, learningStartHour)
            .putInt(AccountPreferencesSyncWorker.KEY_START_MINUTE, learningStartMinute)
            .putBoolean(AccountPreferencesSyncWorker.KEY_REMINDERS, remindersEnabled)
            .putString(AccountPreferencesSyncWorker.KEY_TIMEZONE, TimeZone.getDefault().id)
            .build()
        val request = OneTimeWorkRequestBuilder<AccountPreferencesSyncWorker>()
            .setInputData(input)
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "$PREFERENCES_WORK_PREFIX$userUid",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun deleteLearningData(context: Context) {
        val userUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (BuildConfig.API_BASE_URL.isBlank()) return
        val input = Data.Builder()
            .putString(AccountLearningDataDeleteWorker.KEY_USER_UID, userUid)
            .build()
        val request = OneTimeWorkRequestBuilder<AccountLearningDataDeleteWorker>()
            .setInputData(input)
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "$DELETE_WORK_PREFIX$userUid",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private const val PREFERENCES_WORK_PREFIX = "zen_account_preferences_"
    private const val DELETE_WORK_PREFIX = "zen_account_delete_learning_data_"
}
