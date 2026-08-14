package com.weather.metro.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolEndpointsTest {
    @Test
    fun `tool origins stay https and production scoped`() {
        assertEquals("https://radar.max-yu.workers.dev", ToolEndpoints.RAIN_ORIGIN)
        assertEquals("https://storm.max-yu.workers.dev", ToolEndpoints.STORM_ORIGIN)
        assertTrue(ToolEndpoints.RAIN_ORIGIN.startsWith("https://"))
        assertTrue(ToolEndpoints.STORM_ORIGIN.startsWith("https://"))
    }

    @Test
    fun `rain endpoint builders preserve production contract`() {
        assertEquals(
            "https://radar.max-yu.workers.dev/api/capabilities",
            ToolEndpoints.rainCapabilities(),
        )
        assertEquals(
            "https://radar.max-yu.workers.dev/api/rain/point?lat=22.5&lon=114.1&radiusKm=2",
            ToolEndpoints.rainPoint(22.5, 114.1, 2),
        )
        assertEquals(
            "https://radar.max-yu.workers.dev/api/rain/swirls/frame?frame=15",
            ToolEndpoints.rainSwirlsFrame(15),
        )
        assertEquals(
            "https://radar.max-yu.workers.dev/api/radar/frames?range=64&height=2&mode=live",
            ToolEndpoints.rainRadarFrames(64, 2),
        )
        assertEquals(
            "https://radar.max-yu.workers.dev/api/radar/frames?range=64&height=3&mode=test",
            ToolEndpoints.rainRadarFrames(64, 3, RainRadarMode.TEST),
        )
        assertEquals(
            "https://radar.max-yu.workers.dev/api/radar/image?id=test",
            ToolEndpoints.rainRadarImage("/api/radar/image?id=test"),
        )
        assertEquals(
            "https://radar.max-yu.workers.dev/api/radar/test-image?range=64&frame=0",
            ToolEndpoints.rainRadarImage("/api/radar/test-image?range=64&frame=0"),
        )
    }

    @Test
    fun `rain builders reject invalid client input`() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolEndpoints.rainPoint(22.5, 114.1, 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolEndpoints.rainSwirlsFrame(16)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolEndpoints.rainRadarImage("https://example.com/radar.png")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolEndpoints.rainRadarImage("/api/rain/nowcast")
        }
    }

    @Test
    fun `storm history builders encode backend identifiers`() {
        assertEquals(
            "https://storm.max-yu.workers.dev/health",
            ToolEndpoints.stormHealth(),
        )
        assertEquals(
            "https://storm.max-yu.workers.dev/api/cwa",
            ToolEndpoints.stormCwaLive(),
        )
        assertEquals(
            "https://storm.max-yu.workers.dev/api/history/storms?limit=100",
            ToolEndpoints.stormHistoryStorms(),
        )
        assertEquals(
            "https://storm.max-yu.workers.dev/api/history/storms/2026%20A%2FB",
            ToolEndpoints.stormHistoryStorm("2026 A/B"),
        )
        assertEquals(
            "https://storm.max-yu.workers.dev/api/history/storms/alpha/advisories?limit=200",
            ToolEndpoints.stormHistoryAdvisories("alpha"),
        )
        assertEquals(
            "https://storm.max-yu.workers.dev/api/history/advisories/adv%201",
            ToolEndpoints.stormHistoryAdvisory("adv 1"),
        )
    }
}
