package com.weather.metro.ui.storm

import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormPoint
import com.weather.metro.domain.storm.StormPointType
import com.weather.metro.domain.storm.StormTrack
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StormPointInteractionTest {
    @Test
    fun mapPointGeoJsonCarriesStableSelectionReferenceAndIntensityColour() {
        val track = sampleTrack()
        val data = buildStormAgencyMapData(listOf(track))
        val analysis = JSONObject(data.analysisPoints)
            .getJSONArray("features")
            .getJSONObject(0)
            .getJSONObject("properties")
        val forecast = JSONObject(data.forecastPoints)
            .getJSONArray("features")
            .getJSONObject(0)
            .getJSONObject("properties")

        assertEquals("CMA", analysis.getString("agency"))
        assertEquals("CMA:202608", analysis.getString("storm"))
        assertEquals("analysis", analysis.getString("kind"))
        assertEquals("0", analysis.getString("index"))
        assertEquals("#FA6800", analysis.getString("color"))
        assertEquals("forecast", forecast.getString("kind"))
        assertEquals("0", forecast.getString("index"))
        assertEquals("#FA6800", forecast.getString("color"))
    }

    @Test
    fun selectionResolvesAgainstCurrentSnapshotAndKeepsPopupAnchor() {
        val track = sampleTrack()
        val ref = StormMapPointRef(
            agency = StormAgency.CMA,
            stableKey = track.stableKey,
            pointType = StormPointType.FORECAST,
            pointIndex = 0,
            anchorXPx = 320f,
            anchorYPx = 640f,
        )

        val selected = resolveStormPointSelection(ref, mapOf(StormAgency.CMA to listOf(track)))

        assertEquals(track, selected?.track)
        assertEquals(track.forecastPoints.first(), selected?.point)
        assertEquals(320f, selected?.ref?.anchorXPx)
        assertEquals(640f, selected?.ref?.anchorYPx)
    }

    @Test
    fun selectionFailsClosedWhenPointDisappears() {
        val ref = StormMapPointRef(
            agency = StormAgency.CMA,
            stableKey = "CMA:missing",
            pointType = StormPointType.ANALYSIS,
            pointIndex = 0,
        )

        assertNull(resolveStormPointSelection(ref, mapOf(StormAgency.CMA to listOf(sampleTrack()))))
    }

    @Test
    fun metricsExposeAvailableOfficialFieldsOnly() {
        val text = stormPointMetrics(sampleTrack().forecastPoints.first())

        assertTrue(text.contains("颱風"))
        assertTrue(text.contains("最大風速 38 m/s"))
        assertTrue(text.contains("950 hPa"))
        assertTrue(text.contains("+24 h"))
        assertTrue(text.contains("預報圓 120 km"))
    }

    private fun sampleTrack(): StormTrack = StormTrack(
        stableKey = "CMA:202608",
        agency = StormAgency.CMA,
        agencyStormId = "202608",
        internationalNumber = "2608",
        nameEn = "NANGKA",
        nameZh = "浪卡",
        bulletinTime = "2026-08-14T09:00:00Z",
        analysisPoints = listOf(
            StormPoint(
                validAt = "2026-08-14T09:00:00Z",
                latitude = 29.0,
                longitude = 154.4,
                pointType = StormPointType.ANALYSIS,
                intensityLabel = "颱風",
                intensityCode = "TY",
                windSpeedMs = 36.0,
                pressureHpa = 955.0,
                forecastHour = null,
                probabilityRadiusKm = null,
            ),
        ),
        forecastPoints = listOf(
            StormPoint(
                validAt = "2026-08-15T09:00:00Z",
                latitude = 30.0,
                longitude = 151.0,
                pointType = StormPointType.FORECAST,
                intensityLabel = "颱風",
                intensityCode = "TY",
                windSpeedMs = 38.0,
                pressureHpa = 950.0,
                forecastHour = 24,
                probabilityRadiusKm = 120.0,
            ),
        ),
    )
}
