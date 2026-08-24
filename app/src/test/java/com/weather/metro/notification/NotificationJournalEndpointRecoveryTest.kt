package com.weather.metro.notification

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationJournalEndpointRecoveryTest {
    @Test
    fun `deleted cached deployment falls back to configured endpoint`() {
        val result = fetchBestNotificationJournalPage(
            endpoints = listOf("cached", "configured"),
        ) { endpoint ->
            if (endpoint == "cached") throw NotificationJournalHttpException(404, "text/html")
            page(latestCursor = 85)
        }

        assertEquals("configured", result.endpoint)
        assertEquals(85L, result.page.latestCursor)
    }

    @Test
    fun `newest live journal wins when an old deployment still returns success`() {
        val result = fetchBestNotificationJournalPage(
            endpoints = listOf("old", "current"),
        ) { endpoint ->
            page(latestCursor = if (endpoint == "old") 46 else 85)
        }

        assertEquals("current", result.endpoint)
        assertEquals(85L, result.page.latestCursor)
    }

    @Test
    fun `all endpoint failures remain concise and never include an HTML response body`() {
        val error = assertThrows(IOException::class.java) {
            fetchBestNotificationJournalPage(
                endpoints = listOf("old", "configured"),
            ) { endpoint ->
                if (endpoint == "old") throw NotificationJournalHttpException(404, "text/html")
                throw NotificationJournalHttpException(503, "application/json")
            }
        }

        assertTrue(error.message.orEmpty().contains("HTTP 404 (text/html)"))
        assertTrue(error.message.orEmpty().contains("HTTP 503 (application/json)"))
        assertTrue(!error.message.orEmpty().contains("<!DOCTYPE"))
    }

    private fun page(latestCursor: Long) = NotificationJournalPage(
        nextCursor = latestCursor,
        latestCursor = latestCursor,
        hasMore = false,
        events = emptyList(),
    )
}
