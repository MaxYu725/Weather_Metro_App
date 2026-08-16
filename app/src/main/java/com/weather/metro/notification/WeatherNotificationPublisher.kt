package com.weather.metro.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.weather.metro.MainActivity
import com.weather.metro.R
import com.weather.metro.data.settings.SettingsRepository

internal const val SOURCE_TYPE_LOCATION_DERIVED = "HKO_LOCATION_DERIVED"
private const val LOCATION_DERIVED_POST_TTL_MS = 90 * 60 * 1000L

internal fun shouldExpireBeforePosting(
    event: WeatherNotificationEvent,
    nowEpochMs: Long,
): Boolean {
    if (event.sourceType != SOURCE_TYPE_LOCATION_DERIVED) return false
    if (event.sentAtEpochMillis <= 0L || nowEpochMs <= 0L) return true
    return nowEpochMs - event.sentAtEpochMillis > LOCATION_DERIVED_POST_TTL_MS
}

class WeatherNotificationPublisher(
    context: Context,
    private val store: NotificationEventStore = NotificationEventStore(context),
) {
    private val applicationContext = context.applicationContext
    private val notifications = NotificationManagerCompat.from(applicationContext)

    /** Returns true only after the event is durably present in the local inbox. */
    fun accept(event: WeatherNotificationEvent): Boolean {
        if (!SettingsRepository.notificationsEnabled(applicationContext)) return false
        store.record(event)
        replayPending()
        return true
    }

    fun replayPending() = synchronized(REPLAY_LOCK) {
        if (!SettingsRepository.notificationsEnabled(applicationContext)) return@synchronized
        NotificationChannels.create(applicationContext)
        val postedIds = mutableSetOf<String>()
        val now = System.currentTimeMillis()
        store.pending().forEach { stored ->
            if (shouldExpireBeforePosting(stored.event, now)) {
                // Derived location conditions are time-sensitive. Archive an old
                // local event rather than surfacing it hours after the condition.
                // Official journal publications deliberately do not use this TTL.
                postedIds += stored.event.eventId
                return@forEach
            }
            if (!canPost(stored.event.channel)) return@forEach
            runCatching { post(stored.event) }
                .onSuccess { postedIds += stored.event.eventId }
        }
        store.markPosted(postedIds)
    }

    private fun canPost(channel: String): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!notifications.areNotificationsEnabled()) return false
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val importance = manager.getNotificationChannel(channel)?.importance ?: return false
        return importance != NotificationManager.IMPORTANCE_NONE
    }

    @SuppressLint("MissingPermission")
    private fun post(event: WeatherNotificationEvent) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            action = "com.weather.metro.NOTIFICATION.${event.eventId}"
            data = event.target.toUri()
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            event.eventId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, event.channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(event.title)
            .setContentText(event.body.lineSequence().firstOrNull().orEmpty())
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(event.title)
                    .bigText(event.body)
                    .setSummaryText(summaryText(event)),
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(
                when (event.channel) {
                    NotificationChannels.URGENT -> NotificationCompat.CATEGORY_ALARM
                    NotificationChannels.STATUS -> NotificationCompat.CATEGORY_STATUS
                    else -> NotificationCompat.CATEGORY_EVENT
                },
            )
            .setPriority(
                if (event.channel == NotificationChannels.URGENT) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                },
            )
            .setGroup(NOTIFICATION_GROUP)
            .setWhen(event.sentAtEpochMillis)
            .build()

        notifications.notify(event.eventId, NOTIFICATION_ID, notification)
    }

    private fun summaryText(event: WeatherNotificationEvent): String =
        if (event.sourceType == SOURCE_TYPE_LOCATION_DERIVED) {
            "根據香港天文台公開數據"
        } else {
            "香港天文台官方內容"
        }

    private companion object {
        const val NOTIFICATION_GROUP = "hko_weather_updates"
        const val NOTIFICATION_ID = 0
        val REPLAY_LOCK = Any()
    }
}
