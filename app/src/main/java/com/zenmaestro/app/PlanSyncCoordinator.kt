package com.zenmaestro.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

object PlanSyncCoordinator {

    fun enqueue(context: Context, userUid: String) {
        if (BuildConfig.API_BASE_URL.isBlank()) return
        val input = Data.Builder()
            .putString(PlanSyncWorker.KEY_USER_UID, userUid)
            .build()
        val request = OneTimeWorkRequestBuilder<PlanSyncWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "$WORK_PREFIX$userUid",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private const val WORK_PREFIX = "zen_plan_sync_"
}
