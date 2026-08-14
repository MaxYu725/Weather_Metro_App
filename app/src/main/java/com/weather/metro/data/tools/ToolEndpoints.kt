package com.weather.metro.data.tools

import java.net.URI
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

    private const val HKO_LIST_UPSTREAM = "https://www.weather.gov.hk/wxinfo/currwx/tc_list.xml"
    private const val CMA_LIST_UPSTREAM = "https://typhoon.nmc.cn/weatherservice/typhoon/jsons/list_default"
    private const val JMA_EXTRA_UPSTREAM = "https://www.data.jma.go.jp/developer/xml/feed/extra.xml"
    private const val JMA_EXTRA_LONG_UPSTREAM = "https://www.data.jma.go.jp/developer/xml/feed/extra_l.xml"

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
        val allowed = relativePath.startsWith("/api/radar/image?") ||
            relativePath.startsWith("/api/radar/test-image?")
        require(allowed) { "Radar image must use an approved Rain Worker radar image route" }
        return RAIN_ORIGIN + relativePath
    }

    fun stormHealth(): String = "$STORM_ORIGIN/health"

    fun stormHkoListLive(): String = stormProxy(HKO_LIST_UPSTREAM)

    fun stormHkoTrackLive(rawUrl: String): String {
        val resolved = URI(HKO_LIST_UPSTREAM).resolve(rawUrl).normalize()
        val uri = URI(
            "https",
            resolved.userInfo,
            resolved.host,
            resolved.port,
            resolved.path,
            resolved.query,
            resolved.fragment,
        )
        require(uri.host in setOf("www.weather.gov.hk", "www.hko.gov.hk", "data.weather.gov.hk")) {
            "HKO track host is not approved"
        }
        require(Regex("^/wxinfo/currwx/hko_tctrack_\\d{4}\\.xml$", RegexOption.IGNORE_CASE).matches(uri.path)) {
            "HKO track path is not approved"
        }
        return stormProxy(uri.toASCIIString())
    }

    fun stormCmaListLive(timestampMillis: Long): String {
        require(timestampMillis >= 0L) { "Invalid CMA timestamp" }
        return stormProxy("$CMA_LIST_UPSTREAM?t=$timestampMillis&callback=typhoon_jsons_list_default")
    }

    fun stormCmaDetailLive(stormId: String, timestampMillis: Long): String {
        require(stormId.matches(Regex("^[A-Za-z0-9_-]{1,40}$"))) { "Invalid CMA storm id" }
        require(timestampMillis >= 0L) { "Invalid CMA timestamp" }
        val callback = "typhoon_jsons_view_${stormId.replace(Regex("[^A-Za-z0-9_]"), "_")}" 
        val upstream = "https://typhoon.nmc.cn/weatherservice/typhoon/jsons/view_$stormId?t=$timestampMillis&callback=$callback"
        return stormProxy(upstream)
    }

    fun stormJmaFeedLive(longFeed: Boolean = false): String =
        stormProxy(if (longFeed) JMA_EXTRA_LONG_UPSTREAM else JMA_EXTRA_UPSTREAM)

    fun stormJmaDocumentLive(rawUrl: String): String {
        val resolved = URI(JMA_EXTRA_UPSTREAM).resolve(rawUrl).normalize()
        val uri = URI(
            "https",
            resolved.userInfo,
            resolved.host,
            resolved.port,
            resolved.path,
            resolved.query,
            resolved.fragment,
        )
        require(uri.host == "www.data.jma.go.jp") { "JMA document host is not approved" }
        require(
            Regex(
                "^/developer/xml/data/[A-Za-z0-9_.-]*VPTW6[0-5][A-Za-z0-9_.-]*\\.xml$",
                RegexOption.IGNORE_CASE,
            ).matches(uri.path),
        ) { "JMA document is not an approved VPTW60-65 XML" }
        return stormProxy(uri.toASCIIString())
    }

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

    private fun stormProxy(upstreamUrl: String): String =
        "$STORM_ORIGIN/?url=${URLEncoder.encode(upstreamUrl, "UTF-8").replace("+", "%20")}" 

    private fun encodePathSegment(value: String): String {
        require(value.isNotBlank()) { "Path identifier must not be blank" }
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
    }
}
