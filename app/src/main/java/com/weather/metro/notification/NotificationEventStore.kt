package com.weather.metro.notification

import android.content.Context

data class NotificationRecordResult(
    val changed: Boolean,
    val wasPosted: Boolean,
)

class NotificationEventStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun record(
        event: WeatherNotificationEvent,
        receivedAtEpochMillis: Long = System.currentTimeMillis(),
    ): NotificationRecordResult = synchronized(LOCK) {
        val current = read()
        val existing = current.firstOrNull { it.event.eventId == event.eventId }
        val updated = NotificationInboxCodec.addOrUpgrade(current, event, receivedAtEpochMillis)
        val changed = updated != current
        if (changed) persist(updated)
        NotificationRecordResult(
            changed = changed,
            wasPosted = existing?.posted == true,
        )
    }

    fun pending(): List<StoredNotificationEvent> = synchronized(LOCK) {
        read()
            .filterNot(StoredNotificationEvent::posted)
            .sortedBy(StoredNotificationEvent::receivedAtEpochMillis)
    }

    fun markPosted(eventIds: Set<String>) {
        if (eventIds.isEmpty()) return
        synchronized(LOCK) {
            val current = read()
            val updated = NotificationInboxCodec.markPosted(current, eventIds)
            if (updated != current) persist(updated)
        }
    }

    fun markFullSyncRequired() {
        requireCommit(
            preferences.edit().putBoolean(KEY_FULL_SYNC_REQUIRED, true).commit(),
            "Failed to persist notification reconciliation flag",
        )
    }

    fun consumeFullSyncRequired(): Boolean {
        if (!preferences.getBoolean(KEY_FULL_SYNC_REQUIRED, false)) return false
        requireCommit(
            preferences.edit().putBoolean(KEY_FULL_SYNC_REQUIRED, false).commit(),
            "Failed to clear notification reconciliation flag",
        )
        return true
    }

    private fun read(): List<StoredNotificationEvent> =
        NotificationInboxCodec.decode(preferences.getString(KEY_EVENTS, null))

    private fun persist(events: List<StoredNotificationEvent>) {
        requireCommit(
            preferences.edit().putString(KEY_EVENTS, NotificationInboxCodec.encode(events)).commit(),
            "Failed to persist notification inbox",
        )
    }

    private fun requireCommit(success: Boolean, message: String) {
        if (!success) throw IllegalStateException(message)
    }

    private companion object {
        const val PREFERENCES_NAME = "weather_notification_inbox"
        const val KEY_EVENTS = "events_v1"
        const val KEY_FULL_SYNC_REQUIRED = "full_sync_required"
        val LOCK = Any()
    }
}
