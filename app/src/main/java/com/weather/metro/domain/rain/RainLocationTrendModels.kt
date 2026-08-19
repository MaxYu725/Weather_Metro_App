package com.weather.metro.domain.rain

/**
 * One location sample from one native SWIRLS forecast frame.
 *
 * The sample cadence is six minutes, but [amountMm] remains the rainfall accumulated over the
 * rolling 30-minute window ending at [validTime]. It is never an independent six-minute total.
 */
data class RainLocationTrendSample(
    val frameIndex: Int,
    val runTime: String?,
    val validTime: String,
    val leadMinutes: Int,
    val windowStart: String,
    val windowEnd: String,
    val cadenceMinutes: Int,
    val accumulationMinutes: Int,
    val unit: String,
    val latitude: Double,
    val longitude: Double,
    val interpolation: String,
    val amountMm: Double,
    val clampedToGridCentreBoundary: Boolean,
)
