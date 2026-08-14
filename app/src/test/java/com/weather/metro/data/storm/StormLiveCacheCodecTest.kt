package com.weather.metro.data.storm

import com.weather.metro.domain.storm.AgencyLiveResult
import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormLiveState
import com.weather.metro.domain.storm.StormPoint
import com.weather.metro.domain.storm.StormPointType
import com.weather.metro.domain.storm.StormTrack
import com.weather.metro.domain.storm.StormWindRadii
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StormLiveCacheCodecTest {
    @Test
    fun roundTripsNormalizedLiveSnapshot() {
        val result = AgencyLiveResult(
            agency = StormAgency.CWA,
            state = StormLiveState.OK,
            message = "1 active storm",
            updatedAt = "2026-08-14T06:00:00Z",
            storms = listOf(
                StormTrack(
                    stableKey = "CWA:2026-08-ALPHA",
                    agency = StormAgency.CWA,
                    agencyStormId = "2026-08-ALPHA",
                    internationalNumber = "2608",
                    nameEn = "ALPHA",
                    nameZh = "阿爾法",
                    bulletinTime = "2026-08-14T06:00:00Z",
                    analysisPoints = listOf(
                        StormPoint(
                            validAt = "2026-08-14T06:00:00Z",
                            latitude = 21.5,
                            longitude = 122.0,
                            pointType = StormPointType.ANALYSIS,
                            intensityLabel = "颱風",
                            intensityCode = "TY",
                            windSpeedMs = 35.0,
                            pressureHpa = 965.0,
                            forecastHour = null,
                            probabilityRadiusKm = null,
                            maximumGustMs = 48.0,
                            movingSpeedKmh = 18.0,
                            movingDirection = "NW",
                            movementPrediction = "西北移動",
                            stateTransfer = "維持強度",
                            windRadii = listOf(
                                StormWindRadii(
                                    level = "15 m/s",
                                    northEastKm = 180.0,
                                    southEastKm = 160.0,
                                    southWestKm = 150.0,
                                    northWestKm = 170.0,
                                ),
                            ),
                        ),
                    ),
                    forecastPoints = listOf(
                        StormPoint(
                            validAt = "2026-08-15T06:00:00Z",
                            latitude = 22.5,
                            longitude = 120.5,
                            pointType = StormPointType.FORECAST,
                            intensityLabel = "強烈熱帶風暴",
                            intensityCode = null,
                            windSpeedMs = 28.0,
                            pressureHpa = 980.0,
                            forecastHour = 24,
                            probabilityRadiusKm = 80.0,
                        ),
                    ),
                ),
            ),
        )
        val cached = CachedStormAgency(result = result, savedAtMillis = 1_786_713_600_000L)

        val decoded = StormLiveCacheCodec.decode(StormLiveCacheCodec.encode(cached))

        assertEquals(cached, decoded)
    }

    @Test
    fun roundTripsSuccessfulEmptySnapshot() {
        val cached = CachedStormAgency(
            result = AgencyLiveResult(
                agency = StormAgency.HKO,
                state = StormLiveState.EMPTY,
                message = "No active HKO track",
                updatedAt = null,
                storms = emptyList(),
            ),
            savedAtMillis = 1234L,
        )

        assertEquals(cached, StormLiveCacheCodec.decode(StormLiveCacheCodec.encode(cached)))
    }

    @Test
    fun rejectsUnsupportedVersionAndErrorState() {
        assertThrows(IllegalArgumentException::class.java) {
            StormLiveCacheCodec.decode(
                """{"version":2,"savedAtMillis":1,"result":{"agency":"HKO","state":"EMPTY","storms":[]}}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StormLiveCacheCodec.decode(
                """{"version":1,"savedAtMillis":1,"result":{"agency":"HKO","state":"ERROR","storms":[]}}""",
            )
        }
    }
}
