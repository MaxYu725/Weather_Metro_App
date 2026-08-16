package com.weather.metro.notification

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Immediate one-shot scheduler for the SWIRLS personalised-rain runtime.
 *
 * There is intentionally no PeriodicWorkRequest here. Periodic evaluation runs directly inside
 * the existing 2D1 location-weather worker when its cadence marker is present. This one-shot is
 * reserved for explicit activation/startup/location-refresh evaluation.
 */
object PersonalizedRainScheduler {
    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<PersonalizedRainWorker>()
            .setConstraints(networkConstraint)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    fun reset(context: Context) {
        disable(context)
        PersonalizedRainEpisodeStateStore(context).reset()
        NotificationEventStore(context).discardPendingBySourceType(SOURCE_TYPE_PERSONALIZED_RAIN)
    }

    private const val WORK_NAME = "weather-personalized-rain-now"
}
