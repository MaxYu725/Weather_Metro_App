package com.weather.metro.data.rain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RainSwirlsPointClientTest {
    @Test
    fun parsesRollingThirtyMinuteSampleAtSixMinuteCadence() {
        val sample = RainSwirlsPointClient().parseSample(
            payload = SAMPLE_FIXTURE,
            expectedFrameIndex = 3,
            expectedLatitude = 22.3023,
            expectedLongitude = 114.1746,
        )

        assertEquals(3, sample.frameIndex)
        assertEquals(48, sample.leadMinutes)
        assertEquals(6, sample.cadenceMinutes)
        assertEquals(30, sample.accumulationMinutes)
        assertEquals("mm / 30 min", sample.unit)
        assertEquals(1.234, sample.amountMm, 0.000001)
        assertEquals("2026-08-19T05:12:00.000Z", sample.validTime)
        assertEquals("2026-08-19T04:42:00.000Z", sample.windowStart)
        assertEquals("2026-08-19T05:12:00.000Z", sample.windowEnd)
        assertFalse(sample.clampedToGridCentreBoundary)
    }

    @Test
    fun rejectsIndependentSixMinuteRainfallSemantics() {
        val broken = SAMPLE_FIXTURE.replace("mm / 30 min", "mm / 6 min")
        assertTrue(runCatching { RainSwirlsPointClient().parseSample(broken) }.isFailure)
    }

    @Test
    fun rejectsWrongRequestedFrameOrLocation() {
        assertTrue(
            runCatching {
                RainSwirlsPointClient().parseSample(
                    SAMPLE_FIXTURE,
                    expectedFrameIndex = 4,
                    expectedLatitude = 22.3023,
                    expectedLongitude = 114.1746,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                RainSwirlsPointClient().parseSample(
                    SAMPLE_FIXTURE,
                    expectedFrameIndex = 3,
                    expectedLatitude = 22.4,
                    expectedLongitude = 114.1746,
                )
            }.isFailure,
        )
    }

    @Test
    fun clientLoadsExactlyOneCompactFrameEndpointWithWorkerCompatibleTimeouts() = runBlocking {
        val transport = RecordingTransport(SAMPLE_FIXTURE)
        val sample = RainSwirlsPointClient(transport).loadSample(3, 22.3023, 114.1746)

        assertEquals(
            "https://radar.max-yu.workers.dev/api/rain/swirls/point?frame=3&lat=22.3023&lon=114.1746",
            transport.lastUrl,
        )
        assertEquals(5_000, transport.lastConnectTimeoutMs)
        assertEquals(18_000, transport.lastReadTimeoutMs)
        assertEquals(3, sample.value.frameIndex)
    }

    private class RecordingTransport(private val response: String) : RainHttpTransport {
        var lastUrl: String? = null
        var lastConnectTimeoutMs: Int? = null
        var lastReadTimeoutMs: Int? = null

        override suspend fun get(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String {
            lastUrl = url
            lastConnectTimeoutMs = connectTimeoutMs
            lastReadTimeoutMs = readTimeoutMs
            return response
        }
    }

    companion object {
        private val SAMPLE_FIXTURE = """
            {
              "ok": true,
              "contractVersion": 1,
              "frameIndex": 3,
              "runTime": "2026-08-19T04:24:00.000Z",
              "validTime": "2026-08-19T05:12:00.000Z",
              "leadMinutes": 48,
              "windowStart": "2026-08-19T04:42:00.000Z",
              "windowEnd": "2026-08-19T05:12:00.000Z",
              "cadenceMinutes": 6,
              "accumulationMinutes": 30,
              "unit": "mm / 30 min",
              "location": { "lat": 22.3023, "lon": 114.1746 },
              "interpolation": "bilinear-grid-centres",
              "amountMm": 1.234,
              "clampedToGridCentreBoundary": false,
              "generatedAt": "2026-08-19T04:42:15.000Z"
            }
        """.trimIndent()
    }
}
