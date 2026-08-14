package com.weather.metro.data.storm

import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormPointType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StormServiceTest {
    @Test
    fun parsesHistoryStormListUsingProductionFieldNames() {
        val storms = StormService().parseHistoryStorms(HISTORY_FIXTURE)

        assertEquals(2, storms.size)
        assertEquals("2026-ALPHA", storms[0].id)
        assertEquals(2026, storms[0].year)
        assertEquals("2601", storms[0].internationalNumber)
        assertEquals("Alpha", storms[0].nameEn)
        assertEquals("阿爾法", storms[0].nameZh)
        assertEquals("active", storms[0].status)
        assertEquals(7, storms[0].advisoryCount)
        assertEquals("2026-08-14T00:00:00Z", storms[0].firstSeenAt)
    }

    @Test
    fun parsesStormDetailAndAdvisoryList() {
        val service = StormService()
        val detail = service.parseStorm(STORM_DETAIL_FIXTURE)
        val advisories = service.parseAdvisories(
            payload = ADVISORIES_FIXTURE,
            expectedStormId = "2026-ALPHA",
        )

        assertEquals("2026-ALPHA", detail.storm.id)
        assertEquals(2, advisories.size)
        assertEquals(StormAgency.HKO, advisories[0].agency)
        assertEquals("2026-ALPHA", advisories[0].stormId)
        assertEquals(5, advisories[0].pointCount)
        assertEquals("hko-v3", advisories[0].parserVersion)
        assertEquals(StormAgency.CWA, advisories[1].agency)
    }

    @Test
    fun parsesAdvisoryDetailIntoNormalizedPoints() {
        val detail = StormService().parseAdvisory(ADVISORY_DETAIL_FIXTURE)

        assertEquals("adv-hko-1", detail.advisory.id)
        assertEquals(StormAgency.HKO, detail.advisory.agency)
        assertEquals(2, detail.advisory.pointCount)
        assertEquals(2, detail.points.size)

        val analysis = detail.points[0]
        assertEquals(StormPointType.ANALYSIS, analysis.pointType)
        assertEquals(22.1, analysis.latitude, 0.000001)
        assertEquals(118.5, analysis.longitude, 0.000001)
        assertEquals(35.0, analysis.windSpeedMs!!, 0.000001)
        assertEquals(965.0, analysis.pressureHpa!!, 0.000001)
        assertEquals(null, analysis.forecastHour)

        val forecast = detail.points[1]
        assertEquals(StormPointType.FORECAST, forecast.pointType)
        assertEquals(24, forecast.forecastHour)
        assertEquals(70.0, forecast.probabilityRadiusKm!!, 0.000001)
    }

    @Test
    fun healthAcceptsExplicitOkResponse() {
        val health = StormService().parseHealth(
            """{"ok":true,"version":"3.3.1","generatedAt":"2026-08-14T12:00:00Z"}""",
        )

        assertTrue(health.ok)
        assertEquals("3.3.1", health.version)
        assertEquals("2026-08-14T12:00:00Z", health.checkedAt)
    }

    @Test
    fun serviceUsesOnlyDocumentedHistoryRoutesAndTimeout() = runBlocking {
        val transport = RecordingStormTransport(HISTORY_FIXTURE)
        val service = StormService(transport)

        val result = service.loadHistory(limit = 3)

        assertEquals(
            "https://storm.max-yu.workers.dev/api/history/storms?limit=3",
            transport.lastUrl,
        )
        assertEquals(10_000, transport.lastConnectTimeoutMs)
        assertEquals(16_000, transport.lastReadTimeoutMs)
        assertEquals(2, result.value.size)
        assertFalse(result.rawPayload.isBlank())
    }

    @Test
    fun advisoryRoutePreservesBackendIdentifierEncoding() = runBlocking {
        val transport = RecordingStormTransport(ADVISORY_DETAIL_FIXTURE)
        val service = StormService(transport)

        service.loadAdvisory("adv 1/A")

        assertEquals(
            "https://storm.max-yu.workers.dev/api/history/advisories/adv%201%2FA",
            transport.lastUrl,
        )
    }

    @Test
    fun rejectsUnknownAgencyAndExplicitWorkerFailure() {
        val unknownAgency = ADVISORIES_FIXTURE.replace("\"HKO\"", "\"UNKNOWN\"")
        assertTrue(
            runCatching {
                StormService().parseAdvisories(unknownAgency, expectedStormId = "2026-ALPHA")
            }.isFailure,
        )
        assertTrue(
            runCatching {
                StormService().parseHistoryStorms("""{"ok":false,"error":"database unavailable"}""")
            }.isFailure,
        )
    }

    private class RecordingStormTransport(
        private val response: String,
    ) : StormHttpTransport {
        var lastUrl: String? = null
        var lastConnectTimeoutMs: Int? = null
        var lastReadTimeoutMs: Int? = null

        override suspend fun getText(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String {
            lastUrl = url
            lastConnectTimeoutMs = connectTimeoutMs
            lastReadTimeoutMs = readTimeoutMs
            return response
        }
    }

    companion object {
        private val HISTORY_FIXTURE = """
            {
              "storms": [
                {
                  "id": "2026-ALPHA",
                  "year": 2026,
                  "international_number": "2601",
                  "name_en": "Alpha",
                  "name_zh": "阿爾法",
                  "status": "active",
                  "first_seen_at": "2026-08-14T00:00:00Z",
                  "last_seen_at": "2026-08-14T12:00:00Z",
                  "advisory_count": 7
                },
                {
                  "id": "2025-BETA",
                  "year": 2025,
                  "international_number": "2520",
                  "name_en": "Beta",
                  "name_zh": "貝塔",
                  "status": "archived",
                  "first_seen_at": "2025-09-01T00:00:00Z",
                  "last_seen_at": "2025-09-04T00:00:00Z",
                  "advisory_count": 18
                }
              ]
            }
        """.trimIndent()

        private val STORM_DETAIL_FIXTURE = """
            {
              "storm": {
                "id": "2026-ALPHA",
                "year": 2026,
                "international_number": "2601",
                "name_en": "Alpha",
                "name_zh": "阿爾法",
                "status": "active",
                "first_seen_at": "2026-08-14T00:00:00Z",
                "last_seen_at": "2026-08-14T12:00:00Z",
                "advisory_count": 7
              }
            }
        """.trimIndent()

        private val ADVISORIES_FIXTURE = """
            {
              "advisories": [
                {
                  "id": "adv-hko-1",
                  "agency": "HKO",
                  "issued_at": "2026-08-14T06:00:00Z",
                  "point_count": 5,
                  "parser_version": "hko-v3",
                  "source_code": "HKO"
                },
                {
                  "id": "adv-cwa-1",
                  "storm_id": "2026-ALPHA",
                  "agency": "CWA",
                  "issued_at": "2026-08-14T07:00:00Z",
                  "point_count": 8,
                  "parser_version": "cwa-v2"
                }
              ]
            }
        """.trimIndent()

        private val ADVISORY_DETAIL_FIXTURE = """
            {
              "advisory": {
                "id": "adv-hko-1",
                "storm_id": "2026-ALPHA",
                "agency": "HKO",
                "issued_at": "2026-08-14T06:00:00Z",
                "parser_version": "hko-v3",
                "source_code": "HKO"
              },
              "points": [
                {
                  "valid_at": "2026-08-14T06:00:00Z",
                  "latitude": 22.1,
                  "longitude": 118.5,
                  "point_type": "analysis",
                  "intensity_label": "TY",
                  "intensity_code": "TY",
                  "wind_ms": 35,
                  "pressure_hpa": 965,
                  "forecast_hour": null,
                  "probability_radius_km": null
                },
                {
                  "valid_at": "2026-08-15T06:00:00Z",
                  "latitude": 23.0,
                  "longitude": 116.8,
                  "point_type": "forecast",
                  "intensity_label": "STS",
                  "intensity_code": "STS",
                  "wind_ms": 28,
                  "pressure_hpa": 980,
                  "forecast_hour": 24,
                  "probability_radius_km": 70
                }
              ]
            }
        """.trimIndent()
    }
}
