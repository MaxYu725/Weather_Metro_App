package com.weather.metro.ui.rain

import kotlin.math.abs

/**
 * Returns the next playback slot while skipping frames that failed automatic lazy loading
 * during the current forecast run. The current slot is never returned as a fallback: if
 * every other slot is unavailable, playback should stop instead of spinning on one frame.
 */
internal fun nextForecastPlaybackIndex(
    currentIndex: Int,
    frameCount: Int,
    failedIndexes: Set<Int>,
): Int? {
    if (frameCount <= 1) return null
    val normalizedCurrent = currentIndex.coerceIn(0, frameCount - 1)
    for (offset in 1 until frameCount) {
        val candidate = (normalizedCurrent + offset) % frameCount
        if (candidate !in failedIndexes) return candidate
    }
    return null
}

/**
 * Keeps the same forecast lead when a new SWIRLS run replaces the active timeline.
 * Exact lead matches are preferred; if a source exposes a slightly different lead list,
 * use the nearest available lead rather than forcing the user back to +0 minutes.
 */
internal fun alignedForecastFrameIndex(
    leadMinutes: List<Int>,
    preferredLeadMinutes: Int?,
): Int {
    if (leadMinutes.isEmpty()) return -1
    val preferred = preferredLeadMinutes ?: return 0
    val exact = leadMinutes.indexOf(preferred)
    if (exact >= 0) return exact
    return leadMinutes.indices.minByOrNull { index -> abs(leadMinutes[index] - preferred) } ?: 0
}
