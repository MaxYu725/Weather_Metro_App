package com.weather.metro.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationInboxPersonalizedTest {
    @Test
    fun `disabling personalized source removes only unposted derived events`() {
        val derivedPending = stored("derived-pending", SOURCE_TYPE_LOCATION_DERIVED, posted = false, receivedAt = 1)
        val derivedPosted = stored("derived-posted", SOURCE_TYPE_LOCATION_DERIVED, posted = true, receivedAt = 2)
        val officialPending = stored("official-pending", "WARNING", posted = false, receivedAt = 3)

        val result = NotificationInboxCodec.discardPendingBySourceType(
            listOf(derivedPending, derivedPosted, officialPending),
            SOURCE_TYPE_LOCATION_DERIVED,
        )

        assertEquals(2, result.size)
        assertTrue(result.any { it.event.eventId == "derived-posted" && it.posted })
        assertTrue(result.any { it.event.eventId == "official-pending" && !it.posted })
    }

    @Test
    fun `only stale location-derived events expire before posting`() {
        val now = 10_000_000L
        val recentDerived = event("recent", SOURCE_TYPE_LOCATION_DERIVED, now - 60 * 60 * 1000L)
        val staleDerived = event("stale", SOURCE_TYPE_LOCATION_DERIVED, now - 91 * 60 * 1000L)
        val oldOfficial = event("official", "WARNING", now - 24 * 60 * 60 * 1000L)

        assertFalse(shouldExpireBeforePosting(recentDerived, now))
        assertTrue(shouldExpireBeforePosting(staleDerived, now))
        assertFalse(shouldExpireBeforePosting(oldOfficial, now))
    }

    private fun stored(
        id: String,
        sourceType: String,
        posted: Boolean,
        receivedAt: Long,
    ) = StoredNotificationEvent(
        event = event(id, sourceType, receivedAt),
        receivedAtEpochMillis = receivedAt,
        posted = posted,
    )

    private fun event(
        id: String,
        sourceType: String,
        sentAt: Long,
    ) = WeatherNotificationEvent(
        eventId = id,
        title = "title",
        body = "body",
        channel = NotificationChannels.GENERAL,
        target = "weathermetro://current",
        sourceType = sourceType,
        sentAtEpochMillis = sentAt,
    )
}
