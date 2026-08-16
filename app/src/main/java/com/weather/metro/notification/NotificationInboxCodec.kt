package com.weather.metro.notification

import org.json.JSONArray
import org.json.JSONObject

data class StoredNotificationEvent(
    val event: WeatherNotificationEvent,
    val receivedAtEpochMillis: Long,
    val posted: Boolean = false,
)

object NotificationInboxCodec {
    private const val MAX_POSTED_HISTORY = 256

    fun decode(value: String?): List<StoredNotificationEvent> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val eventId = item.optString("eventId").takeIf(String::isNotBlank) ?: continue
                    val body = item.optString("body").takeIf(String::isNotBlank) ?: continue
                    add(
                        StoredNotificationEvent(
                            event = WeatherNotificationEvent(
                                eventId = eventId,
                                title = item.optString("title", "香港天文台"),
                                body = body,
                                channel = item.optString("channel", NotificationContract.GENERAL),
                                target = item.optString("target", "weathermetro://current"),
                                alertId = item.optString("alertId"),
                                alertCode = item.optString("alertCode"),
                                eventKind = item.optString("eventKind"),
                                sourceType = item.optString("sourceType"),
                                sourceTime = item.optString("sourceTime"),
                                journalCursor = item.optLong("journalCursor").coerceAtLeast(0L),
                                sentAtEpochMillis = item.optLong("sentAtEpochMillis"),
                            ),
                            receivedAtEpochMillis = item.optLong("receivedAtEpochMillis"),
                            posted = item.optBoolean("posted"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun encode(events: List<StoredNotificationEvent>): String {
        val array = JSONArray()
        events.forEach { stored ->
            array.put(
                JSONObject()
                    .put("eventId", stored.event.eventId)
                    .put("title", stored.event.title)
                    .put("body", stored.event.body)
                    .put("channel", stored.event.channel)
                    .put("target", stored.event.target)
                    .put("alertId", stored.event.alertId)
                    .put("alertCode", stored.event.alertCode)
                    .put("eventKind", stored.event.eventKind)
                    .put("sourceType", stored.event.sourceType)
                    .put("sourceTime", stored.event.sourceTime)
                    .put("journalCursor", stored.event.journalCursor)
                    .put("sentAtEpochMillis", stored.event.sentAtEpochMillis)
                    .put("receivedAtEpochMillis", stored.receivedAtEpochMillis)
                    .put("posted", stored.posted),
            )
        }
        return array.toString()
    }

    fun addIfAbsent(
        events: List<StoredNotificationEvent>,
        event: WeatherNotificationEvent,
        receivedAtEpochMillis: Long,
    ): List<StoredNotificationEvent> = addOrUpgrade(events, event, receivedAtEpochMillis)

    fun addOrUpgrade(
        events: List<StoredNotificationEvent>,
        event: WeatherNotificationEvent,
        receivedAtEpochMillis: Long,
    ): List<StoredNotificationEvent> {
        val existingIndex = events.indexOfFirst { it.event.eventId == event.eventId }
        if (existingIndex < 0) {
            return trim(events + StoredNotificationEvent(event, receivedAtEpochMillis))
        }

        val existing = events[existingIndex]
        if (!shouldUpgrade(existing.event, event)) return events
        val visibleContentChanged = hasVisibleContentChanged(existing.event, event)
        val updated = events.toMutableList().apply {
            // Journal metadata/cursor upgrades must not make an already-posted
            // complete notification appear twice. Repost only when the journal
            // actually replaces user-visible preview/routing content.
            this[existingIndex] = existing.copy(
                event = event,
                posted = existing.posted && !visibleContentChanged,
            )
        }
        return trim(updated)
    }

    fun markPosted(events: List<StoredNotificationEvent>, eventIds: Set<String>): List<StoredNotificationEvent> =
        trim(events.map { stored ->
            if (stored.event.eventId in eventIds) stored.copy(posted = true) else stored
        })

    /**
     * Removes only not-yet-posted events for a disabled personalized source.
     * Posted history remains available for dedupe; unrelated official journal
     * events are never touched.
     */
    fun discardPendingBySourceType(
        events: List<StoredNotificationEvent>,
        sourceType: String,
    ): List<StoredNotificationEvent> = trim(
        events.filterNot { stored ->
            !stored.posted && stored.event.sourceType == sourceType
        },
    )

    private fun shouldUpgrade(
        existing: WeatherNotificationEvent,
        incoming: WeatherNotificationEvent,
    ): Boolean {
        if (incoming.journalCursor <= 0L) return false
        if (existing.journalCursor > incoming.journalCursor) return false
        return existing != incoming
    }

    private fun hasVisibleContentChanged(
        existing: WeatherNotificationEvent,
        incoming: WeatherNotificationEvent,
    ): Boolean =
        existing.title != incoming.title ||
            existing.body != incoming.body ||
            existing.channel != incoming.channel ||
            existing.target != incoming.target

    private fun trim(events: List<StoredNotificationEvent>): List<StoredNotificationEvent> {
        val pending = events.filterNot(StoredNotificationEvent::posted)
        val posted = events.filter(StoredNotificationEvent::posted)
            .sortedByDescending(StoredNotificationEvent::receivedAtEpochMillis)
            .take(MAX_POSTED_HISTORY)
        return (pending + posted).sortedBy(StoredNotificationEvent::receivedAtEpochMillis)
    }
}
