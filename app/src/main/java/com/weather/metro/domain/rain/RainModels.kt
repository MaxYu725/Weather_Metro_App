package com.weather.metro.domain.rain

data class RainCapabilities(
    val workerVersion: String,
    val pointForecast: Boolean,
    val nowcast: Boolean,
    val radarFrames: Boolean,
    val swirlsFrames: Boolean,
    val swirlsContract: SwirlsContract?,
)

data class SwirlsContract(
    val frameCount: Int,
    val cadenceMinutes: Int,
    val accumulationMinutes: Int,
)

data class RainPointForecast(
    val workerVersion: String,
    val unit: String,
    val issueTime: String?,
    val generatedAt: String?,
    val nearbyRadiusKm: Int?,
    val summary: RainPointSummary?,
    val periods: List<RainPeriod>,
    val quality: RainDataQuality?,
)

data class RainPointSummary(
    val text: String?,
    val totalMm: Double?,
    val peakMm: Double?,
    val rainStartWindowStart: String?,
    val rainStartWindowEnd: String?,
)

data class RainPeriod(
    val time: String,
    val amountMm: Double,
    val nearbyMaxMm: Double,
    val nearbyMeanMm: Double?,
    val level: String?,
)

data class RainDataQuality(
    val freshness: RainFreshness?,
    val spatial: RainSpatialQuality?,
)

data class RainFreshness(
    val status: String,
    val label: String?,
    val note: String?,
    val sourceAgeMinutes: Double?,
)

data class RainSpatialQuality(
    val status: String,
    val label: String?,
    val note: String?,
    val nearbyDeltaMaxMm: Double?,
)

data class RainLoadResult<T>(
    val value: T,
    val isStale: Boolean,
    val networkError: String? = null,
)
