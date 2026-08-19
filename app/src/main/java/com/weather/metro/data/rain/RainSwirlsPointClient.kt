package com.weather.metro.data.rain

import com.weather.metro.data.tools.ToolEndpoints
import com.weather.metro.domain.rain.RainLocationTrendSample
import org.json.JSONObject
import kotlin.math.abs

internal fun rainSwirlsPointUrl(
    frameIndex: Int,
    latitude: Double,
    longitude: Double,
): String {
    require(frameIndex in 0..15) { "SWIRLS frame index must be 0..15" }
    require(latitude in -90.0..90.0) { "Invalid latitude" }
    require(longitude in -180.0..180.0) { "Invalid longitude" }
    return "${ToolEndpoints.RAIN_ORIGIN}/api/rain/swirls/point?frame=$frameIndex&lat=$latitude&lon=$longitude"
}

class RainSwirlsPointClient internal constructor(
    private val transport: RainHttpTransport = UrlConnectionRainTransport(),
) {
    suspend fun loadSample(
        frameIndex: Int,
        latitude: Double,
        longitude: Double,
    ): RainNetworkResult<RainLocationTrendSample> {
        val payload = transport.get(
            rainSwirlsPointUrl(frameIndex, latitude, longitude),
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
        return RainNetworkResult(
            value = parseSample(payload, frameIndex, latitude, longitude),
            rawPayload = payload,
        )
    }

    fun parseSample(
        payload: String,
        expectedFrameIndex: Int? = null,
        expectedLatitude: Double? = null,
        expectedLongitude: Double? = null,
    ): RainLocationTrendSample {
        val root = JSONObject(payload)
        require(root.optBoolean("ok", false)) {
            "SWIRLS point failed: ${root.optString("error").ifBlank { "unknown Worker error" }}"
        }

        val frameIndex = root.requiredInt("frameIndex")
        require(frameIndex in 0..15) { "Unexpected SWIRLS frame index: $frameIndex" }
        expectedFrameIndex?.let { require(frameIndex == it) { "SWIRLS point frame mismatch" } }

        val cadence = root.requiredInt("cadenceMinutes")
        val accumulation = root.requiredInt("accumulationMinutes")
        val unit = root.requiredString("unit")
        require(cadence == 6) { "Unexpected SWIRLS cadence: $cadence" }
        require(accumulation == 30) { "Unexpected SWIRLS accumulation window: $accumulation" }
        require(unit == RAIN_UNIT) { "Unexpected rainfall unit: $unit" }

        val amount = root.requiredFiniteDouble("amountMm")
        require(amount >= 0.0) { "SWIRLS point rainfall must be non-negative" }

        val location = root.optJSONObject("location") ?: error("SWIRLS point location missing")
        val latitude = location.requiredFiniteDouble("lat")
        val longitude = location.requiredFiniteDouble("lon")
        expectedLatitude?.let { requireClose(latitude, it, "latitude") }
        expectedLongitude?.let { requireClose(longitude, it, "longitude") }

        return RainLocationTrendSample(
            frameIndex = frameIndex,
            runTime = root.optNonBlankString("runTime"),
            validTime = root.requiredString("validTime"),
            leadMinutes = root.requiredInt("leadMinutes"),
            windowStart = root.requiredString("windowStart"),
            windowEnd = root.requiredString("windowEnd"),
            cadenceMinutes = cadence,
            accumulationMinutes = accumulation,
            unit = unit,
            latitude = latitude,
            longitude = longitude,
            interpolation = root.requiredString("interpolation"),
            amountMm = amount,
            clampedToGridCentreBoundary = root.optBoolean("clampedToGridCentreBoundary", false),
        )
    }

    private fun JSONObject.requiredString(key: String): String =
        optString(key).takeIf { it.isNotBlank() && it != "null" }
            ?: error("Required field '$key' missing")

    private fun JSONObject.optNonBlankString(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.requiredInt(key: String): Int {
        require(has(key) && !isNull(key)) { "Required integer field '$key' missing" }
        return getInt(key)
    }

    private fun JSONObject.requiredFiniteDouble(key: String): Double {
        require(has(key) && !isNull(key)) { "Required numeric field '$key' missing" }
        val value = getDouble(key)
        require(value.isFinite()) { "Numeric field '$key' is not finite" }
        return value
    }

    private fun requireClose(actual: Double, expected: Double, label: String) {
        require(abs(actual - expected) <= 0.000001) {
            "SWIRLS point $label mismatch: expected $expected, got $actual"
        }
    }

    companion object {
        // The Worker may spend up to 12 s on an upstream SWIRLS fetch. This client is a
        // background enhancement, so its read bound must outlive the Worker deadline without
        // affecting the independent Current fast path.
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 18_000
        private const val RAIN_UNIT = "mm / 30 min"
    }
}
