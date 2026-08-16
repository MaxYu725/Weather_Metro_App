package com.weather.metro.notification

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object LocationHeavyRainScheduler {
    const val SOURCE_TYPE = "HKO_LOCATION_DERIVED"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<LocationHeavyRainWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<LocationHeavyRainWorker>()
            .setConstraints(networkConstraint)
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context.applicationContext).apply {
            cancelUniqueWork(PERIODIC_WORK_NAME)
            cancelUniqueWork(IMMEDIATE_WORK_NAME)
        }
    }

    fun reset(context: Context) {
        disable(context)
        LocationHeavyRainStateStore(context).reset()
        NotificationEventStore(context).discardPendingBySourceType(SOURCE_TYPE)
    }

    private const val PERIODIC_WORK_NAME = "weather-location-heavy-rain-periodic"
    private const val IMMEDIATE_WORK_NAME = "weather-location-heavy-rain-now"
}
