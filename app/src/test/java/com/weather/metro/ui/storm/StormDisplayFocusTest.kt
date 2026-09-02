package com.weather.metro.ui.storm

import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormPoint
import com.weather.metro.domain.storm.StormPointType
import com.weather.metro.domain.storm.StormTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StormDisplayFocusTest {
    @Test
    fun sameNamedStormAcrossFourAgenciesBecomesOneFocusGroup() {
        val tracks = mapOf(
            StormAgency.HKO to listOf(track(StormAgency.HKO, "SAUDEL", "沙德爾", 20.0, 118.0)),
            StormAgency.CMA to listOf(track(StormAgency.CMA, "SAUDEL", "沙德爾", 20.1, 118.2)),
            StormAgency.JMA to listOf(track(StormAgency.JMA, "SAUDEL", null, 20.2, 118.1)),
            StormAgency.CWA to listOf(track(StormAgency.CWA, "SAUDEL", "沙德爾", 20.0, 118.3)),
        )

        val groups = buildStormDisplayGroups(tracks)

        assertEquals(1, groups.size)
        assertEquals(4, groups.single().agencyCount)
        assertTrue(groups.single().displayName.contains("SAUDEL"))
    }

    @Test
    fun distinctNamedStormsRemainSeparateAndNearestHongKongSortsFirst() {
        val near = track(StormAgency.HKO, "NEAR", "近港", 21.8, 115.0)
        val far = track(StormAgency.CWA, "FAR", "遠海", 18.0, 135.0)

        val groups = buildStormDisplayGroups(
            mapOf(
                StormAgency.HKO to listOf(near),
                StormAgency.CWA to listOf(far),
            ),
        )

        assertEquals(2, groups.size)
        assertTrue(groups.first().displayName.contains("NEAR"))
        assertTrue((groups.first().nearestHongKongKm ?: Double.MAX_VALUE) < (groups.last().nearestHongKongKm ?: 0.0))
    }

    @Test
    fun unnamedNearbyAgencyTrackCanJoinNamedStormWithoutChangingSourceIdentity() {
        val named = track(StormAgency.HKO, "SAUDEL", "沙德爾", 20.0, 118.0)
        val generic = track(StormAgency.CMA, "TD15", "熱帶低氣壓", 20.2, 118.1)

        val groups = buildStormDisplayGroups(
            mapOf(
                StormAgency.HKO to listOf(named),
                StormAgency.CMA to listOf(generic),
            ),
        )

        assertEquals(1, groups.size)
        assertEquals("HKO:SAUDEL", groups.single().tracksByAgency[StormAgency.HKO].orEmpty().single().stableKey)
        assertEquals("CMA:TD15", groups.single().tracksByAgency[StormAgency.CMA].orEmpty().single().stableKey)
    }

    private fun track(
        agency: StormAgency,
        nameEn: String,
        nameZh: String?,
        lat: Double,
        lon: Double,
    ): StormTrack {
        val point = StormPoint(
            validAt = "2026-09-02T04:00:00Z",
            latitude = lat,
            longitude = lon,
            pointType = StormPointType.ANALYSIS,
            intensityLabel = "TS",
            intensityCode = "TS",
            windSpeedMs = 20.0,
            pressureHpa = 990.0,
            forecastHour = null,
            probabilityRadiusKm = null,
        )
        return StormTrack(
            stableKey = "${agency.name}:$nameEn",
            agency = agency,
            agencyStormId = nameEn,
            internationalNumber = null,
            nameEn = nameEn,
            nameZh = nameZh,
            bulletinTime = point.validAt,
            analysisPoints = listOf(point),
            forecastPoints = emptyList(),
        )
    }
}
