package com.weather.metro.notification

import android.content.Context

class NotificationEventStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun record(event: WeatherNotificationEvent, receivedAtEpochMillis: Long = System.currentTimeMillis()) =
        synchronized(LOCK) {
            val updated = NotificationInboxCodec.addIfAbsent(read(), event, receivedAtEpochMillis)
            persist(updated)
        }

    fun pending(): List<StoredNotificationEvent> = synchronized(LOCK) {
        read()
            .filterNot(StoredNotificationEvent::posted)
            .sortedBy(StoredNotificationEvent::receivedAtEpochMillis)
    }

    fun markPosted(eventIds: Set<String>) {
        if (eventIds.isEmpty()) return
        synchronized(LOCK) {
            persist(NotificationInboxCodec.markPosted(read(), eventIds))
        }
    }

    fun markFullSyncRequired() {
        preferences.edit().putBoolean(KEY_FULL_SYNC_REQUIRED, true).commit()
    }

    fun consumeFullSyncRequired(): Boolean {
        if (!preferences.getBoolean(KEY_FULL_SYNC_REQUIRED, false)) return false
        return preferences.edit().putBoolean(KEY_FULL_SYNC_REQUIRED, false).commit()
    }

    private fun read(): List<StoredNotificationEvent> =
        NotificationInboxCodec.decode(preferences.getString(KEY_EVENTS, null))

    private fun persist(events: List<StoredNotificationEvent>) {
        preferences.edit().putString(KEY_EVENTS, NotificationInboxCodec.encode(events)).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "weather_notification_inbox"
        const val KEY_EVENTS = "events_v1"
        const val KEY_FULL_SYNC_REQUIRED = "full_sync_required"
        val LOCK = Any()
    }
}
