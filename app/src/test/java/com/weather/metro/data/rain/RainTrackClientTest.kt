package com.weather.metro.data.rain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RainTrackClientTest {
    @Test
    fun parsesProductionCapabilityShape() {
        val value = RainTrackClient().parseCapabilities(CAPABILITIES_FIXTURE)

        assertEquals("2.5.0", value.workerVersion)
        assertTrue(value.pointForecast)
        assertTrue(value.nowcast)
        assertTrue(value.radarFrames)
        assertTrue(value.swirlsFrames)
        assertEquals(16, value.swirlsContract?.frameCount)
        assertEquals(6, value.swirlsContract?.cadenceMinutes)
        assertEquals(30, value.swirlsContract?.accumulationMinutes)
    }

    @Test
    fun parsesPointForecastWithoutUiDependencies() {
        val value = RainTrackClient().parsePointForecast(
            payload = POINT_FIXTURE,
            expectedLatitude = 22.4992,
            expectedLongitude = 114.1467,
            expectedRadiusKm = 2,
        )

        assertEquals("2.5.0", value.workerVersion)
        assertEquals("mm / 30 min", value.unit)
        assertEquals(22.4992, value.location?.latitude ?: 0.0, 0.000001)
        assertEquals(114.1467, value.location?.longitude ?: 0.0, 0.000001)
        assertEquals(2.0, value.nearbyRadiusKm ?: 0.0, 0.000001)
        assertEquals("bilinear-four-grid-points", value.interpolation)
        assertNotNull(value.grid)
        assertEquals(4, value.periods.size)
        assertEquals(30, value.periods.first().leadMinutes)
        assertEquals(7.9, value.periods.first().amountMm, 0.0001)
        assertEquals(8.6, value.periods.first().nearbyMaxMm, 0.0001)
        assertEquals("heavy", value.periods.first().level)
        assertEquals(12.0, value.summary?.totalMm ?: 0.0, 0.0001)
        assertEquals(2, value.summary?.wetPeriodCount)
        assertEquals("delayed", value.quality?.freshness?.status)
        assertEquals("sensitive", value.quality?.spatial?.status)
    }

    @Test
    fun rejectsUnexpectedRainfallUnit() {
        val broken = POINT_FIXTURE.replace("mm / 30 min", "mm / 6 min")

        val result = runCatching { RainTrackClient().parsePointForecast(broken) }

        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsNegativeRainfall() {
        val broken = POINT_FIXTURE.replace("\"amountMm\": 7.9", "\"amountMm\": -1.0")

        val result = runCatching { RainTrackClient().parsePointForecast(broken) }

        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsResponseForDifferentRequestedLocation() {
        val result = runCatching {
            RainTrackClient().parsePointForecast(
                payload = POINT_FIXTURE,
                expectedLatitude = 22.3000,
                expectedLongitude = 114.1467,
                expectedRadiusKm = 2,
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun clientBuildsPointEndpointThroughRegistry() = runBlocking {
        val transport = RecordingTransport(POINT_FIXTURE)
        val client = RainTrackClient(transport)

        val result = client.loadPointForecast(22.4992, 114.1467, 2)

        assertEquals("https://radar.max-yu.workers.dev/api/rain/point?lat=22.4992&lon=114.1467&radiusKm=2", transport.lastUrl)
        assertFalse(result.rawPayload.isBlank())
        assertEquals(4, result.value.periods.size)
    }

    private class RecordingTransport(private val response: String) : RainHttpTransport {
        var lastUrl: String? = null

        override suspend fun get(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String {
            lastUrl = url
            assertEquals(10_000, connectTimeoutMs)
            assertEquals(20_000, readTimeoutMs)
            return response
        }
    }

    companion object {
        private val CAPABILITIES_FIXTURE = """
            {
              "ok": true,
              "version": "2.5.0",
              "capabilities": {
                "pointForecast": true,
                "nowcastGrid": true,
                "swirlsFrames": true,
                "radarFrames": true
              },
              "swirlsContract": {
                "frameCount": 16,
                "cadenceMinutes": 6,
                "accumulationMinutes": 30
              }
            }
        """.trimIndent()

        private val POINT_FIXTURE = """
            {
              "ok": true,
              "version": "2.5.0",
              "source": "Hong Kong Observatory gridded rainfall nowcast",
              "sourceUpdatedAt": "2026-08-14T04:36:00.000Z",
              "generatedAt": "2026-08-14T04:40:00.000Z",
              "issueTime": "2026-08-14T04:36:00.000Z",
              "unit": "mm / 30 min",
              "location": { "lat": 22.4992, "lon": 114.1467 },
              "nearbyRadiusKm": 2,
              "interpolation": "bilinear-four-grid-points",
              "grid": { "minLat": 20.0, "maxLat": 25.0, "minLon": 111.0, "maxLon": 117.0 },
              "summary": {
                "text": "可能於 12:36–13:06 期間開始有雨。",
                "totalMm": 12.0,
                "peakMm": 7.9,
                "peakTime": "2026-08-14T05:06:00.000Z",
                "peakWindowStart": "2026-08-14T04:36:00.000Z",
                "peakWindowEnd": "2026-08-14T05:06:00.000Z",
                "rainStartTime": "2026-08-14T05:06:00.000Z",
                "rainStartWindowStart": "2026-08-14T04:36:00.000Z",
                "rainStartWindowEnd": "2026-08-14T05:06:00.000Z",
                "rainStartLeadMinutes": 30,
                "rainEndTime": "2026-08-14T05:36:00.000Z",
                "rainEndWindowStart": "2026-08-14T05:06:00.000Z",
                "rainEndWindowEnd": "2026-08-14T05:36:00.000Z",
                "wetPeriodCount": 2
              },
              "dataQuality": {
                "freshness": {
                  "status": "delayed",
                  "label": "更新稍有延遲",
                  "note": "官方網格資料基準已超過18分鐘。",
                  "sourceAgeMinutes": 24
                },
                "spatial": {
                  "status": "sensitive",
                  "label": "雨區邊界接近",
                  "note": "附近網格雨量差異較大。",
                  "nearbyDeltaMaxMm": 2.4,
                  "maxSpatialSpreadMm": 3.1
                }
              },
              "periods": [
                { "time": "2026-08-14T05:06:00.000Z", "leadMinutes": 30, "amountMm": 7.9, "nearbyMaxMm": 8.6, "nearbyMeanMm": 6.2, "nearestGridKm": 0.8, "spatialSpreadMm": 3.1, "level": "heavy" },
                { "time": "2026-08-14T05:36:00.000Z", "leadMinutes": 60, "amountMm": 2.6, "nearbyMaxMm": 3.1, "nearbyMeanMm": 2.2, "nearestGridKm": 0.8, "spatialSpreadMm": 1.4, "level": "heavy" },
                { "time": "2026-08-14T06:06:00.000Z", "leadMinutes": 90, "amountMm": 1.4, "nearbyMaxMm": 2.2, "nearbyMeanMm": 1.1, "nearestGridKm": 0.8, "spatialSpreadMm": 0.8, "level": "moderate" },
                { "time": "2026-08-14T06:36:00.000Z", "leadMinutes": 120, "amountMm": 0.1, "nearbyMaxMm": 0.2, "nearbyMeanMm": 0.1, "nearestGridKm": 0.8, "spatialSpreadMm": 0.2, "level": "dry" }
              ]
            }
        """.trimIndent()
    }
}
