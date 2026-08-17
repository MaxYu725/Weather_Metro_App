package com.weather.metro.notification

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.weather.metro.data.settings.SettingsRepository
import java.util.concurrent.TimeUnit

object NotificationReconcileScheduler {
    internal const val IMMEDIATE_WORK_NAME = "notification-journal-reconcile"
    internal const val PERIODIC_WORK_NAME = "notification-journal-safety-net"

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueueNow(context: Context) {
        val applicationContext = context.applicationContext
        if (!SettingsRepository.notificationsEnabled(applicationContext)) return
        val request = OneTimeWorkRequestBuilder<NotificationJournalWorker>()
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            // A foreground/app/FCM wake-up means "reconcile now". KEEP can leave a
            // previous failed request parked in exponential backoff, so a fresh
            // wake-up would not actually perform a fresh journal read. Replacing
            // the one-shot request resets that stale backoff while the periodic
            // safety net remains independently owned below.
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun ensurePeriodic(context: Context) {
        val applicationContext = context.applicationContext
        if (!SettingsRepository.notificationsEnabled(applicationContext)) {
            disable(applicationContext)
            return
        }
        val request = PeriodicWorkRequestBuilder<NotificationJournalWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun disable(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
    }
}
