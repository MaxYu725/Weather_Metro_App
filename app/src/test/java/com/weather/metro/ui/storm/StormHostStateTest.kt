package com.weather.metro.ui.storm

import com.weather.metro.domain.storm.AgencyLiveResult
import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormLiveState
import com.weather.metro.domain.storm.StormPoint
import com.weather.metro.domain.storm.StormPointType
import com.weather.metro.domain.storm.StormTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StormHostStateTest {
    @Test
    fun sourceFailureRetainsPreviousSuccessfulSnapshot() {
        val previous = StormAgencyHostState(
            agency = StormAgency.JMA,
            liveState = StormLiveState.OK,
            message = "1 active storm",
            updatedAt = "2026-08-14T06:00:00Z",
            storms = listOf(track(StormAgency.JMA)),
            refreshing = true,
            hasSuccessfulSnapshot = true,
            isCached = false,
        )
        val incoming = AgencyLiveResult(
            agency = StormAgency.JMA,
            state = StormLiveState.ERROR,
            message = "JMA upstream timeout",
            updatedAt = null,
            storms = emptyList(),
        )

        val merged = mergeStormAgencyResult(previous, incoming)

        assertEquals(StormLiveState.STALE, merged.liveState)
        assertEquals(previous.storms, merged.storms)
        assertEquals(previous.updatedAt, merged.updatedAt)
        assertEquals("JMA upstream timeout", merged.errorMessage)
        assertTrue(merged.hasSuccessfulSnapshot)
        assertFalse(merged.refreshing)
    }

    @Test
    fun successfulEmptyResultReplacesOldStormsAndCountsAsSnapshot() {
        val previous = StormAgencyHostState(
            agency = StormAgency.HKO,
            liveState = StormLiveState.STALE,
            message = "cached",
            updatedAt = "2026-08-14T00:00:00Z",
            storms = listOf(track(StormAgency.HKO)),
            hasSuccessfulSnapshot = true,
            isCached = true,
        )
        val incoming = AgencyLiveResult(
            agency = StormAgency.HKO,
            state = StormLiveState.EMPTY,
            message = "No active HKO track",
            updatedAt = null,
            storms = emptyList(),
        )

        val merged = mergeStormAgencyResult(previous, incoming)

        assertEquals(StormLiveState.EMPTY, merged.liveState)
        assertTrue(merged.storms.isEmpty())
        assertTrue(merged.hasSuccessfulSnapshot)
        assertFalse(merged.isCached)
        assertEquals(null, merged.errorMessage)
    }

    @Test
    fun failureWithoutSnapshotBecomesSourceOnlyError() {
        val previous = StormAgencyHostState(agency = StormAgency.CMA)
        val incoming = AgencyLiveResult(
            agency = StormAgency.CMA,
            state = StormLiveState.ERROR,
            message = "CMA failed",
            updatedAt = null,
            storms = emptyList(),
        )

        val merged = mergeStormAgencyResult(previous, incoming)

        assertEquals(StormLiveState.ERROR, merged.liveState)
        assertFalse(merged.hasSuccessfulSnapshot)
        assertEquals("CMA failed", merged.errorMessage)
    }

    private fun track(agency: StormAgency): StormTrack = StormTrack(
        stableKey = "${agency.name}:test",
        agency = agency,
        agencyStormId = "test",
        internationalNumber = null,
        nameEn = "TEST",
        nameZh = null,
        bulletinTime = "2026-08-14T06:00:00Z",
        analysisPoints = listOf(
            StormPoint(
                validAt = "2026-08-14T06:00:00Z",
                latitude = 20.0,
                longitude = 120.0,
                pointType = StormPointType.ANALYSIS,
                intensityLabel = null,
                intensityCode = null,
                windSpeedMs = null,
                pressureHpa = null,
                forecastHour = null,
                probabilityRadiusKm = null,
            ),
        ),
        forecastPoints = emptyList(),
    )
}
