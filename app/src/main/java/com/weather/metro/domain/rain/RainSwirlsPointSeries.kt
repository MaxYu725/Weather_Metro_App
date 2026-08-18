package com.weather.metro.domain.rain

data class RainSwirlsPointSeries(
    val workerVersion: String,
    val runTime: String,
    val cadenceMinutes: Int,
    val accumulationMinutes: Int,
    val unit: String,
    val location: RainPointLocation,
    val interpolation: String,
    val sampleCount: Int,
    val peakAccumulationMm: Double,
    val peakLeadMinutes: Int,
    val firstWetLeadMinutes: Int?,
    val samples: List<RainSwirlsPointSample>,
)

data class RainSwirlsPointSample(
    val frameIndex: Int,
    val validTime: String,
    val leadMinutes: Int,
    val windowStart: String,
    val windowEnd: String,
    val accumulationMm: Double,
    val spatialSpreadMm: Double,
)
