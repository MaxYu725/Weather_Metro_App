package com.weather.metro.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationInboxPersonalizedTest {
    @Test
    fun `disabling personalized source removes only unposted derived events`() {
        val derivedPending = stored("derived-pending", "HKO_LOCATION_DERIVED", posted = false, receivedAt = 1)
        val derivedPosted = stored("derived-posted", "HKO_LOCATION_DERIVED", posted = true, receivedAt = 2)
        val officialPending = stored("official-pending", "WARNING", posted = false, receivedAt = 3)

        val result = NotificationInboxCodec.discardPendingBySourceType(
            listOf(derivedPending, derivedPosted, officialPending),
            "HKO_LOCATION_DERIVED",
        )

        assertEquals(2, result.size)
        assertTrue(result.any { it.event.eventId == "derived-posted" && it.posted })
        assertTrue(result.any { it.event.eventId == "official-pending" && !it.posted })
    }

    private fun stored(
        id: String,
        sourceType: String,
        posted: Boolean,
        receivedAt: Long,
    ) = StoredNotificationEvent(
        event = WeatherNotificationEvent(
            eventId = id,
            title = "title",
            body = "body",
            channel = NotificationChannels.GENERAL,
            target = "weathermetro://current",
            sourceType = sourceType,
            sentAtEpochMillis = receivedAt,
        ),
        receivedAtEpochMillis = receivedAt,
        posted = posted,
    )
}
