package com.weather.metro.notification

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationJournalCodecTest {
    @Test
    fun `journal parser preserves full source body beyond FCM preview size`() {
        val fullBody = "香港天文台完整內容。".repeat(1_000)
        val page = NotificationJournalCodec.decodePage(
            pageJson(
                events = listOf(eventJson(cursor = 1, body = fullBody)),
                nextCursor = 1,
                latestCursor = 1,
                hasMore = false,
            ),
        )

        assertEquals(1, page.events.size)
        assertEquals(fullBody, page.events.single().body)
        assertTrue(page.events.single().body.length > 8_000)
        assertEquals(1L, page.events.single().journalCursor)
    }

    @Test
    fun `journal parser rejects unsafe routing instead of skipping the event`() {
        val unsafe = eventJson(cursor = 1, body = "內容").put("target", "https://example.invalid/")
        assertThrows(IllegalArgumentException::class.java) {
            NotificationJournalCodec.decodePage(
                pageJson(listOf(unsafe), nextCursor = 1, latestCursor = 1, hasMore = false),
            )
        }
    }

    @Test
    fun `journal parser requires strictly increasing cursors`() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationJournalCodec.decodePage(
                pageJson(
                    events = listOf(
                        eventJson(cursor = 2, body = "二"),
                        eventJson(cursor = 1, body = "一"),
                    ),
                    nextCursor = 1,
                    latestCursor = 2,
                    hasMore = true,
                ),
            )
        }
    }

    @Test
    fun `journal endpoint accepts only production Apps Script exec URLs`() {
        assertEquals(
            "https://script.google.com/macros/s/test-deployment/exec",
            NotificationJournalState.normaliseEndpoint(
                " https://script.google.com/macros/s/test-deployment/exec ",
            ),
        )
        assertNull(NotificationJournalState.normaliseEndpoint("http://script.google.com/macros/s/test/exec"))
        assertNull(NotificationJournalState.normaliseEndpoint("https://example.com/macros/s/test/exec"))
        assertNull(NotificationJournalState.normaliseEndpoint("https://script.google.com/macros/s/test/dev"))
    }

    @Test
    fun `cached endpoint retains the configured production endpoint as recovery candidate`() {
        assertEquals(
            listOf(
                "https://script.google.com/macros/s/cached/exec",
                "https://script.google.com/macros/s/configured/exec",
            ),
            NotificationJournalState.endpointCandidates(
                stored = "https://script.google.com/macros/s/cached/exec",
                configured = "https://script.google.com/macros/s/configured/exec",
            ),
        )
        assertEquals(
            listOf("https://script.google.com/macros/s/same/exec"),
            NotificationJournalState.endpointCandidates(
                stored = "https://script.google.com/macros/s/same/exec",
                configured = "https://script.google.com/macros/s/same/exec",
            ),
        )
    }

    @Test
    fun `empty current page does not fabricate events`() {
        val page = NotificationJournalCodec.decodePage(
            pageJson(emptyList(), nextCursor = 9, latestCursor = 9, hasMore = false),
        )
        assertTrue(page.events.isEmpty())
        assertFalse(page.hasMore)
        assertEquals(9L, page.nextCursor)
    }

    private fun eventJson(cursor: Long, body: String): JSONObject = JSONObject()
        .put("eventId", "hko:event-$cursor")
        .put("title", "天氣警告")
        .put("body", body)
        .put("channel", NotificationContract.GENERAL)
        .put("target", "weathermetro://current/alerts?code=WTS")
        .put("alertId", "warning:WTS")
        .put("alertCode", "WTS")
        .put("eventKind", "UPDATE")
        .put("sourceType", "WARNING")
        .put("sourceTime", "2026-08-16T10:00:00+08:00")
        .put("sentAtEpochMillis", 1_765_000_000_000L + cursor)
        .put("journalCursor", cursor)

    private fun pageJson(
        events: List<JSONObject>,
        nextCursor: Long,
        latestCursor: Long,
        hasMore: Boolean,
    ): String = JSONObject()
        .put("schemaVersion", 1)
        .put("generatedAtEpochMs", 1_765_000_000_000L)
        .put("nextCursor", nextCursor)
        .put("latestCursor", latestCursor)
        .put("hasMore", hasMore)
        .put("events", JSONArray(events))
        .toString()
}
