package com.weather.metro.data.rain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RainSwirlsPointSeriesClientTest {
    @Test
    fun parsesCompactSixMinuteCadenceSeriesWithoutChangingAccumulationMeaning() {
        val value = RainSwirlsPointSeriesClient().parse(
            payload = validFixture(),
            expectedLatitude = 22.3023,
            expectedLongitude = 114.1746,
        )

        assertEquals("2.6.0", value.workerVersion)
        assertEquals(16, value.sampleCount)
        assertEquals(6, value.cadenceMinutes)
        assertEquals(30, value.accumulationMinutes)
        assertEquals("mm / 30 min", value.unit)
        assertEquals("bilinear-four-grid-points", value.interpolation)
        assertEquals(30, value.samples.first().leadMinutes)
        assertEquals(120, value.samples.last().leadMinutes)
        assertEquals(0.03, value.samples.first().accumulationMm, 0.0001)
        assertEquals(0.21, value.peakAccumulationMm, 0.0001)
        assertEquals(108, value.peakLeadMinutes)
        assertEquals(90, value.firstWetLeadMinutes)
    }

    @Test
    fun acceptsWorkerRoundedLocationWithinTolerance() {
        val rounded = validFixture()
            .replace("22.3023", "22.30231")
            .replace("114.1746", "114.17459")

        val value = RainSwirlsPointSeriesClient().parse(
            payload = rounded,
            expectedLatitude = 22.3023,
            expectedLongitude = 114.1746,
        )

        assertEquals(22.30231, value.location.latitude, 0.000001)
        assertEquals(114.17459, value.location.longitude, 0.000001)
    }

    @Test
    fun preservesDrySeriesWithoutInventingFirstWetLead() {
        val dry = validFixture(
            amounts = List(16) { 0.0 },
            peakAmount = 0.0,
            peakLead = 30,
            firstWetLead = null,
        )

        val value = RainSwirlsPointSeriesClient().parse(dry)

        assertNull(value.firstWetLeadMinutes)
        assertTrue(value.samples.all { it.accumulationMm == 0.0 })
    }

    @Test
    fun rejectsSixMinuteUnitRelabel() {
        val broken = validFixture().replace("mm / 30 min", "mm / 6 min")

        assertTrue(runCatching { RainSwirlsPointSeriesClient().parse(broken) }.isFailure)
    }

    @Test
    fun rejectsWrongSampleLead() {
        val broken = validFixture().replace("\"leadMinutes\": 36", "\"leadMinutes\": 37")

        assertTrue(runCatching { RainSwirlsPointSeriesClient().parse(broken) }.isFailure)
    }

    @Test
    fun rejectsIncompleteSeries() {
        val broken = validFixture().replace("\"sampleCount\": 16", "\"sampleCount\": 15")

        assertTrue(runCatching { RainSwirlsPointSeriesClient().parse(broken) }.isFailure)
    }

    @Test
    fun clientBuildsCompactEndpointThroughRegistry() = runBlocking {
        val transport = RecordingTransport(validFixture())
        val client = RainSwirlsPointSeriesClient(transport)

        val result = client.load(22.3023, 114.1746)

        assertEquals(
            "https://radar.max-yu.workers.dev/api/rain/swirls/point-series?lat=22.3023&lon=114.1746",
            transport.lastUrl,
        )
        assertFalse(result.rawPayload.isBlank())
        assertEquals(16, result.value.samples.size)
    }

    private class RecordingTransport(private val response: String) : RainHttpTransport {
        var lastUrl: String? = null

        override suspend fun get(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String {
            lastUrl = url
            assertEquals(10_000, connectTimeoutMs)
            assertEquals(25_000, readTimeoutMs)
            return response
        }
    }

    companion object {
        private val DEFAULT_AMOUNTS = listOf(
            0.03, 0.02, 0.01, 0.02,
            0.03, 0.04, 0.04, 0.05,
            0.06, 0.08, 0.12, 0.20,
            0.19, 0.21, 0.18, 0.16,
        )

        private fun validFixture(
            amounts: List<Double> = DEFAULT_AMOUNTS,
            peakAmount: Double = 0.21,
            peakLead: Int = 108,
            firstWetLead: Int? = 90,
        ): String {
            require(amounts.size == 16)
            val samples = amounts.mapIndexed { index, amount ->
                val lead = 30 + index * 6
                val validMinute = 30 + index * 6
                val valid = java.time.Instant.parse("2026-08-18T12:00:00Z")
                    .plusSeconds(validMinute * 60L)
                val start = valid.minusSeconds(30 * 60L)
                """    {"frameIndex": $index, "validTime": "$valid", "leadMinutes": $lead, "windowStart": "$start", "windowEnd": "$valid", "accumulationMm": $amount, "spatialSpreadMm": 0.03}"""
            }.joinToString(",\n")
            val firstWet = firstWetLead?.toString() ?: "null"
            return """
                {
                  "ok": true,
                  "version": "2.6.0",
                  "contractVersion": "1.0",
                  "runTime": "2026-08-18T12:00:00.000Z",
                  "cadenceMinutes": 6,
                  "accumulationMinutes": 30,
                  "unit": "mm / 30 min",
                  "location": { "lat": 22.3023, "lon": 114.1746 },
                  "interpolation": "bilinear-four-grid-points",
                  "sampleCount": 16,
                  "peakAccumulationMm": $peakAmount,
                  "peakLeadMinutes": $peakLead,
                  "firstWetLeadMinutes": $firstWet,
                  "samples": [
                $samples
                  ]
                }
            """.trimIndent()
        }
    }
}
