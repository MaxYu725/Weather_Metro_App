package com.weather.metro.data.tools

import java.net.URLEncoder

enum class RainRadarMode(val wireValue: String) {
    LIVE("live"),
    TEST("test"),
}

/**
 * Single source of truth for public Rain/Storm runtime origins used by Weather Metro.
 *
 * UI code must not scatter Worker hosts or construct arbitrary Storm proxy URLs.
 * Backend/admin credentials never belong here.
 */
object ToolEndpoints {
    const val RAIN_ORIGIN = "https://radar.max-yu.workers.dev"
    const val STORM_ORIGIN = "https://storm.max-yu.workers.dev"

    fun rainCapabilities(): String = "$RAIN_ORIGIN/api/capabilities"

    fun rainPoint(
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
    ): String {
        require(radiusKm in setOf(1, 2, 3, 5)) { "Unsupported nearby radius: $radiusKm km" }
        require(latitude in -90.0..90.0) { "Invalid latitude" }
        require(longitude in -180.0..180.0) { "Invalid longitude" }
        return "$RAIN_ORIGIN/api/rain/point?lat=$latitude&lon=$longitude&radiusKm=$radiusKm"
    }

    fun rainNowcast(): String = "$RAIN_ORIGIN/api/rain/nowcast"

    fun rainSwirlsFrame(frameIndex: Int): String {
        require(frameIndex in 0..15) { "SWIRLS frame index must be 0..15" }
        return "$RAIN_ORIGIN/api/rain/swirls/frame?frame=$frameIndex"
    }

    fun rainRadarFrames(
        rangeKm: Int,
        heightKm: Int,
        mode: RainRadarMode = RainRadarMode.LIVE,
    ): String {
        require(rangeKm > 0) { "Radar range must be positive" }
        require(heightKm > 0) { "Radar height must be positive" }
        return "$RAIN_ORIGIN/api/radar/frames?range=$rangeKm&height=$heightKm&mode=${mode.wireValue}"
    }

    fun rainRadarImage(relativePath: String): String {
        require(relativePath.startsWith("/api/radar/image?")) { "Radar image must use the Worker image proxy" }
        return RAIN_ORIGIN + relativePath
    }

    fun stormHealth(): String = "$STORM_ORIGIN/health"

    fun stormCwaLive(): String = "$STORM_ORIGIN/api/cwa"

    fun stormHistoryStorms(limit: Int = 100): String {
        require(limit in 1..200) { "History limit must be 1..200" }
        return "$STORM_ORIGIN/api/history/storms?limit=$limit"
    }

    fun stormHistoryStorm(stormId: String): String =
        "$STORM_ORIGIN/api/history/storms/${encodePathSegment(stormId)}"

    fun stormHistoryAdvisories(
        stormId: String,
        limit: Int = 200,
    ): String {
        require(limit in 1..500) { "Advisory limit must be 1..500" }
        return "$STORM_ORIGIN/api/history/storms/${encodePathSegment(stormId)}/advisories?limit=$limit"
    }

    fun stormHistoryAdvisory(advisoryId: String): String =
        "$STORM_ORIGIN/api/history/advisories/${encodePathSegment(advisoryId)}"

    private fun encodePathSegment(value: String): String {
        require(value.isNotBlank()) { "Path identifier must not be blank" }
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
    }
}
