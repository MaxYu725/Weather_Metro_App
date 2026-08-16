package com.weather.metro.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HkoDistrictRainClientTest {
    private val client = HkoDistrictRainClient()

    @Test
    fun `parser returns matching district past-hour minimum and maximum`() {
        val observation = client.parse(
            payload = """
                {
                  "rainfall": {
                    "data": [
                      {"place":"中西區","min":1,"max":8},
                      {"place":"沙田","min":52,"max":73}
                    ]
                  },
                  "updateTime":"2026-08-16T14:00:00+08:00"
                }
            """.trimIndent(),
            district = "沙田",
        )

        assertEquals("沙田", observation.district)
        assertEquals(52.0, observation.pastHourMinMm ?: -1.0, 0.0)
        assertEquals(73.0, observation.pastHourMaxMm ?: -1.0, 0.0)
        assertEquals("2026-08-16T14:00:00+08:00", observation.observedAt)
    }

    @Test
    fun `missing district remains fail closed`() {
        val observation = client.parse(
            payload = """
                {
                  "rainfall": {"data":[{"place":"中西區","min":0,"max":3}]},
                  "updateTime":"2026-08-16T14:00:00+08:00"
                }
            """.trimIndent(),
            district = "北區",
        )

        assertNull(observation.pastHourMinMm)
        assertNull(observation.pastHourMaxMm)
    }
}
