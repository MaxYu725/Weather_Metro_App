package com.weather.metro.ui.storm

import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormLiveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StormRefreshPolicyTest {
    @Test
    fun successfulSnapshotStaysFreshUntilFifteenMinutes() {
        val source = successfulSource(lastSuccessAtMillis = 1_000L)

        assertFalse(
            stormAgencyNeedsRefresh(
                source = source,
                nowMillis = 1_000L + STORM_SUCCESS_STALE_AFTER_MS - 1L,
            ),
        )
        assertTrue(
            stormAgencyNeedsRefresh(
                source = source,
                nowMillis = 1_000L + STORM_SUCCESS_STALE_AFTER_MS,
            ),
        )
    }

    @Test
    fun recentDeviceCacheUsesSameSuccessStaleThreshold() {
        val source = successfulSource(lastSuccessAtMillis = 20_000L).copy(
            liveState = StormLiveState.STALE,
            isCached = true,
            lastAttemptAtMillis = null,
        )

        assertFalse(
            stormAgencyNeedsRefresh(
                source = source,
                nowMillis = 20_000L + 2L * 60L * 1000L,
            ),
        )
    }

    @Test
    fun failedRefreshUsesFiveMinuteRetryBackoff() {
        val source = successfulSource(lastSuccessAtMillis = 1_000L).copy(
            liveState = StormLiveState.STALE,
            lastAttemptAtMillis = 100_000L,
            errorMessage = "資料來源回應逾時",
        )

        assertFalse(
            stormAgencyNeedsRefresh(
                source = source,
                nowMillis = 100_000L + STORM_FAILURE_RETRY_AFTER_MS - 1L,
            ),
        )
        assertTrue(
            stormAgencyNeedsRefresh(
                source = source,
                nowMillis = 100_000L + STORM_FAILURE_RETRY_AFTER_MS,
            ),
        )
    }

    @Test
    fun sourceWithoutSnapshotLoadsImmediatelyThenBacksOffAfterFailureAttempt() {
        val initial = StormAgencyHostState(agency = StormAgency.JMA)
        assertTrue(stormAgencyNeedsRefresh(initial, nowMillis = 500_000L))

        val attempted = initial.copy(
            liveState = StormLiveState.ERROR,
            lastAttemptAtMillis = 500_000L,
        )
        assertFalse(
            stormAgencyNeedsRefresh(
                source = attempted,
                nowMillis = 500_000L + STORM_FAILURE_RETRY_AFTER_MS - 1L,
            ),
        )
    }

    @Test
    fun policySelectsOnlyExpiredAgencies() {
        val now = 2_000_000L
        val sources = mapOf(
            StormAgency.HKO to successfulSource(
                agency = StormAgency.HKO,
                lastSuccessAtMillis = now - 2L * 60L * 1000L,
            ),
            StormAgency.CMA to successfulSource(
                agency = StormAgency.CMA,
                lastSuccessAtMillis = now - STORM_SUCCESS_STALE_AFTER_MS,
            ),
            StormAgency.JMA to StormAgencyHostState(agency = StormAgency.JMA),
            StormAgency.CWA to successfulSource(
                agency = StormAgency.CWA,
                lastSuccessAtMillis = now - 3L * 60L * 1000L,
            ).copy(refreshing = true),
        )

        assertEquals(
            setOf(StormAgency.CMA, StormAgency.JMA),
            stormAgenciesNeedingRefresh(sources = sources, nowMillis = now),
        )
    }

    @Test
    fun rawTechnicalErrorsAreMappedToCompactUserMessages() {
        assertEquals(
            "資料來源回應逾時",
            stormUserFacingError("java.net.SocketTimeoutException: timeout"),
        )
        assertEquals(
            "資料格式暫時無法讀取",
            stormUserFacingError("This parser does not support specification Unknown"),
        )
        assertEquals(
            "資料來源暫時無法連線",
            stormUserFacingError("HTTP 502 from upstream"),
        )
        assertEquals(
            "即時資料暫時無法更新",
            stormUserFacingError("unexpected upstream state"),
        )
    }

    @Test
    fun lastSuccessAgeLabelIsCompactAndClampsFutureClock() {
        assertEquals("剛更新", stormLastSuccessAgeLabel(10_000L, 9_000L))
        assertEquals("8分前", stormLastSuccessAgeLabel(0L, 8L * 60L * 1000L))
        assertEquals("2小時前", stormLastSuccessAgeLabel(0L, 2L * 60L * 60L * 1000L))
    }

    private fun successfulSource(
        agency: StormAgency = StormAgency.HKO,
        lastSuccessAtMillis: Long,
    ) = StormAgencyHostState(
        agency = agency,
        liveState = StormLiveState.OK,
        message = "updated",
        refreshing = false,
        hasSuccessfulSnapshot = true,
        isCached = false,
        lastSuccessAtMillis = lastSuccessAtMillis,
        lastAttemptAtMillis = null,
    )
}
