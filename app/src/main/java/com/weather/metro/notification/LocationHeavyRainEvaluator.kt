package com.weather.metro.notification

internal enum class LocationHeavyRainLevel(val thresholdMm: Int) {
    NONE(0),
    HEAVY_50(50),
    VERY_HEAVY_70(70),
}

internal data class LocationHeavyRainDecision(
    val observedLevel: LocationHeavyRainLevel,
    val nextActiveLevel: LocationHeavyRainLevel,
    val notificationLevel: LocationHeavyRainLevel?,
)

/**
 * Pure state transition for the location-specific heavy-rain notification.
 *
 * HKO describes its MyObservatory location-specific heavy-rain service as using
 * 50 mm and 70 mm past-hour district rainfall levels. We only notify on an
 * upward crossing. Once 70 mm has been reached, a later 50–69.9 mm reading is
 * treated as the same episode and does not create a downgrade notification.
 * The episode is reset only after the district past-hour maximum falls below
 * 50 mm. Missing/invalid source data never clears an active episode.
 */
internal fun evaluateLocationHeavyRain(
    observedPastHourMaxMm: Double?,
    activeLevel: LocationHeavyRainLevel,
): LocationHeavyRainDecision {
    if (observedPastHourMaxMm == null || !observedPastHourMaxMm.isFinite() || observedPastHourMaxMm < 0.0) {
        return LocationHeavyRainDecision(
            observedLevel = LocationHeavyRainLevel.NONE,
            nextActiveLevel = activeLevel,
            notificationLevel = null,
        )
    }

    val observedLevel = when {
        observedPastHourMaxMm >= LocationHeavyRainLevel.VERY_HEAVY_70.thresholdMm ->
            LocationHeavyRainLevel.VERY_HEAVY_70
        observedPastHourMaxMm >= LocationHeavyRainLevel.HEAVY_50.thresholdMm ->
            LocationHeavyRainLevel.HEAVY_50
        else -> LocationHeavyRainLevel.NONE
    }

    if (observedLevel == LocationHeavyRainLevel.NONE) {
        return LocationHeavyRainDecision(
            observedLevel = observedLevel,
            nextActiveLevel = LocationHeavyRainLevel.NONE,
            notificationLevel = null,
        )
    }

    val notificationLevel = when {
        activeLevel == LocationHeavyRainLevel.NONE -> observedLevel
        activeLevel == LocationHeavyRainLevel.HEAVY_50 &&
            observedLevel == LocationHeavyRainLevel.VERY_HEAVY_70 -> observedLevel
        else -> null
    }
    val nextActiveLevel = when {
        activeLevel == LocationHeavyRainLevel.VERY_HEAVY_70 -> activeLevel
        observedLevel == LocationHeavyRainLevel.VERY_HEAVY_70 -> observedLevel
        else -> LocationHeavyRainLevel.HEAVY_50
    }

    return LocationHeavyRainDecision(
        observedLevel = observedLevel,
        nextActiveLevel = nextActiveLevel,
        notificationLevel = notificationLevel,
    )
}
