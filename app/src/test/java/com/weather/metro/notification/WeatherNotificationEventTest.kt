package com.weather.metro.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherNotificationEventTest {
    @Test
    fun `parser accepts a versioned backend event`() {
        val event = WeatherNotificationEventParser.parse(
            data = mapOf(
                "eventId" to "hko:event-1",
                "title" to "黑色暴雨警告",
                "body" to "警告內容",
                "channel" to NotificationContract.URGENT,
                "target" to "weathermetro://current/alerts?code=WRAINB",
                "alertId" to "warning:WRAINB",
                "alertCode" to "WRAINB",
                "eventKind" to "issue",
                "sourceType" to "warning",
                "sourceTime" to "2026-08-16T10:00:00+08:00",
                "journalCursor" to "17",
                "sentAtEpochMs" to "12345",
            ),
            messageId = "fcm-id",
            notificationTitle = null,
            notificationBody = null,
            nowEpochMillis = 99999,
        )

        requireNotNull(event)
        assertEquals("hko:event-1", event.eventId)
        assertEquals(NotificationContract.URGENT, event.channel)
        assertEquals("ISSUE", event.eventKind)
        assertEquals("WARNING", event.sourceType)
        assertEquals(17L, event.journalCursor)
        assertEquals(12345L, event.sentAtEpochMillis)
    }

    @Test
    fun `parser rejects missing bodies and sanitises untrusted routing fields`() {
        assertNull(
            WeatherNotificationEventParser.parse(
                data = mapOf("title" to "missing body"),
                messageId = "id",
                notificationTitle = null,
                notificationBody = null,
            ),
        )

        val event = WeatherNotificationEventParser.parse(
            data = mapOf(
                "body" to "內容",
                "channel" to "attacker_channel",
                "target" to "https://example.invalid/",
            ),
            messageId = "fcm-id",
            notificationTitle = null,
            notificationBody = null,
            nowEpochMillis = 42,
        )
        requireNotNull(event)
        assertEquals(NotificationContract.GENERAL, event.channel)
        assertEquals("weathermetro://current", event.target)
        assertEquals("fcm-id", event.eventId)
    }

    @Test
    fun `inbox survives encoding, deduplicates retries, and retains every pending event`() {
        val first = event("same")
        var inbox = NotificationInboxCodec.addIfAbsent(emptyList(), first, 1)
        inbox = NotificationInboxCodec.addIfAbsent(inbox, first.copy(title = "retry"), 2)
        assertEquals(1, inbox.size)
        assertEquals("title same", inbox.single().event.title)

        repeat(300) { index ->
            inbox = NotificationInboxCodec.addIfAbsent(inbox, event("pending-$index"), index + 10L)
        }
        assertEquals(301, inbox.size)
        assertTrue(inbox.all { !it.posted })

        val decoded = NotificationInboxCodec.decode(NotificationInboxCodec.encode(inbox))
        assertEquals(inbox, decoded)
        assertFalse(decoded.any(StoredNotificationEvent::posted))
    }

    @Test
    fun `authoritative journal event upgrades a posted FCM preview and is reposted`() {
        val preview = event("same").copy(
            body = "截短預覽…",
            sourceType = "WARNING",
            journalCursor = 7,
        )
        val full = preview.copy(body = "完整天文台正文\n第二段")
        val inbox = listOf(StoredNotificationEvent(preview, receivedAtEpochMillis = 1, posted = true))

        val upgraded = NotificationInboxCodec.addOrUpgrade(inbox, full, 2)

        assertEquals(1, upgraded.size)
        assertEquals("完整天文台正文\n第二段", upgraded.single().event.body)
        assertFalse(upgraded.single().posted)
    }

    @Test
    fun `posted history is bounded without deleting pending events`() {
        var inbox = (0 until 300).map { index ->
            StoredNotificationEvent(event("posted-$index"), index.toLong(), posted = true)
        } + StoredNotificationEvent(event("pending"), 400, posted = false)

        inbox = NotificationInboxCodec.markPosted(inbox, setOf("unknown"))

        assertEquals(257, inbox.size)
        assertTrue(inbox.any { it.event.eventId == "pending" && !it.posted })
        assertEquals(256, inbox.count(StoredNotificationEvent::posted))
    }

    private fun event(id: String) = WeatherNotificationEvent(
        eventId = id,
        title = "title $id",
        body = "body $id",
        channel = NotificationContract.GENERAL,
        target = "weathermetro://current",
        sentAtEpochMillis = 123,
    )
}
