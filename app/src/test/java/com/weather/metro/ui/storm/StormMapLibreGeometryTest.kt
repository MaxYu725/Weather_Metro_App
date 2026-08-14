package com.weather.metro.ui.storm

import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormPoint
import com.weather.metro.domain.storm.StormPointType
import com.weather.metro.domain.storm.StormTrack
import com.weather.metro.domain.storm.StormWindRadii
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class StormMapLibreGeometryTest {
    @Test
    fun probabilityCircleIsClosedAndApproximatelyMatchesRadius() {
        val polygon = stormCirclePolygonCoordinates(
            latitude = 20.0,
            longitude = 130.0,
            radiusKm = 120.0,
            segments = 48,
        )

        assertEquals(49, polygon.size)
        assertEquals(polygon.first(), polygon.last())
        val distance = haversineKm(20.0, 130.0, polygon.first().latitude, polygon.first().longitude)
        assertTrue(kotlin.math.abs(distance - 120.0) < 0.5)
    }

    @Test
    fun quadrantWindPolygonUsesAgencyRadii() {
        val radii = StormWindRadii(
            level = "15 m/s",
            northEastKm = 120.0,
            southEastKm = 90.0,
            southWestKm = 60.0,
            northWestKm = 30.0,
        )
        val polygon = stormWindPolygonCoordinates(
            latitude = 20.0,
            longitude = 130.0,
            radii = radii,
            segments = 8,
        )

        assertEquals(9, polygon.size)
        assertEquals(polygon.first(), polygon.last())
        val expected = listOf(120.0, 120.0, 90.0, 90.0, 60.0, 60.0, 30.0, 30.0)
        polygon.dropLast(1).zip(expected).forEach { (point, radiusKm) ->
            val distance = haversineKm(20.0, 130.0, point.latitude, point.longitude)
            assertTrue(kotlin.math.abs(distance - radiusKm) < 0.5)
        }
    }

    @Test
    fun mapDataConnectsLatestAnalysisToForecastAndBuildsOverlays() {
        val wind = StormWindRadii(
            level = "15 m/s",
            northEastKm = 100.0,
            southEastKm = 90.0,
            southWestKm = 80.0,
            northWestKm = 70.0,
        )
        val analysis = stormPoint(
            time = "2026-08-14T06:00:00Z",
            lat = 20.0,
            lon = 130.0,
            type = StormPointType.ANALYSIS,
            windRadii = listOf(wind),
        )
        val forecast = stormPoint(
            time = "2026-08-15T06:00:00Z",
            lat = 21.0,
            lon = 132.0,
            type = StormPointType.FORECAST,
            probabilityRadiusKm = 140.0,
            forecastHour = 24,
        )
        val track = StormTrack(
            stableKey = "CWA:test",
            agency = StormAgency.CWA,
            agencyStormId = "test",
            internationalNumber = null,
            nameEn = "TEST",
            nameZh = "測試",
            bulletinTime = "2026-08-14T06:00:00Z",
            analysisPoints = listOf(analysis),
            forecastPoints = listOf(forecast),
        )

        val data = buildStormAgencyMapData(listOf(track))
        val forecastFeatures = JSONObject(data.forecastLines).getJSONArray("features")
        val coordinates = forecastFeatures
            .getJSONObject(0)
            .getJSONObject("geometry")
            .getJSONArray("coordinates")

        assertEquals(1, forecastFeatures.length())
        assertEquals(2, coordinates.length())
        assertEquals(1, JSONObject(data.analysisPoints).getJSONArray("features").length())
        assertEquals(1, JSONObject(data.forecastPoints).getJSONArray("features").length())
        assertEquals(1, JSONObject(data.probabilityPolygons).getJSONArray("features").length())
        assertEquals(1, JSONObject(data.windPolygons).getJSONArray("features").length())
        assertTrue(data.boundsCoordinates.size > 10)
    }

    private fun stormPoint(
        time: String,
        lat: Double,
        lon: Double,
        type: StormPointType,
        forecastHour: Int? = null,
        probabilityRadiusKm: Double? = null,
        windRadii: List<StormWindRadii> = emptyList(),
    ): StormPoint = StormPoint(
        validAt = time,
        latitude = lat,
        longitude = lon,
        pointType = type,
        intensityLabel = null,
        intensityCode = null,
        windSpeedMs = null,
        pressureHpa = null,
        forecastHour = forecastHour,
        probabilityRadiusKm = probabilityRadiusKm,
        windRadii = windRadii,
    )

    private fun haversineKm(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val radius = 6371.0088
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2.0) * sin(dLon / 2.0)
        return radius * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }
}
