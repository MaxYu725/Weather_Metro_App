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
    val sourceUpdatedAt: String?,
    val issueTime: String?,
    val generatedAt: String?,
    val location: RainPointLocation?,
    val nearbyRadiusKm: Double?,
    val interpolation: String?,
    val grid: RainGridCoverage?,
    val summary: RainPointSummary?,
    val periods: List<RainPeriod>,
    val quality: RainDataQuality?,
)

data class RainPointLocation(
    val latitude: Double,
    val longitude: Double,
)

data class RainGridCoverage(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
)

data class RainPointSummary(
    val text: String?,
    val totalMm: Double?,
    val peakMm: Double?,
    val peakTime: String?,
    val peakWindowStart: String?,
    val peakWindowEnd: String?,
    val rainStartTime: String?,
    val rainStartWindowStart: String?,
    val rainStartWindowEnd: String?,
    val rainStartLeadMinutes: Int?,
    val rainEndTime: String?,
    val rainEndWindowStart: String?,
    val rainEndWindowEnd: String?,
    val wetPeriodCount: Int?,
)

data class RainPeriod(
    val time: String,
    val leadMinutes: Int?,
    val amountMm: Double,
    val nearbyMaxMm: Double,
    val nearbyMeanMm: Double?,
    val nearestGridKm: Double?,
    val spatialSpreadMm: Double?,
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
    val maxSpatialSpreadMm: Double?,
)

data class RainLoadResult<T>(
    val value: T,
    val isStale: Boolean,
    val networkError: String? = null,
)
