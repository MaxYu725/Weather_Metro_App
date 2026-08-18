package com.weather.metro.data.rain

import com.weather.metro.data.tools.ToolEndpoints
import com.weather.metro.data.tools.rainSwirlsPointSeries
import com.weather.metro.domain.rain.RainPointLocation
import com.weather.metro.domain.rain.RainSwirlsPointSample
import com.weather.metro.domain.rain.RainSwirlsPointSeries
import org.json.JSONObject
import kotlin.math.abs

class RainSwirlsPointSeriesClient internal constructor(
    private val transport: RainHttpTransport = UrlConnectionRainTransport(),
) {
    suspend fun load(
        latitude: Double,
        longitude: Double,
    ): RainNetworkResult<RainSwirlsPointSeries> {
        val payload = transport.get(
            ToolEndpoints.rainSwirlsPointSeries(latitude, longitude),
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
        return RainNetworkResult(
            value = parse(payload, latitude, longitude),
            rawPayload = payload,
        )
    }

    fun parse(
        payload: String,
        expectedLatitude: Double? = null,
        expectedLongitude: Double? = null,
    ): RainSwirlsPointSeries {
        val root = JSONObject(payload)
        require(root.optBoolean("ok", false)) {
            "SWIRLS point series failed: ${root.optString("error").ifBlank { "unknown Worker error" }}"
        }
        val version = root.requiredString("version")
        val runTime = root.requiredString("runTime")
        val cadence = root.optInt("cadenceMinutes", -1)
        val accumulation = root.optInt("accumulationMinutes", -1)
        val unit = root.requiredString("unit")
        require(cadence == EXPECTED_CADENCE_MINUTES) { "Unexpected SWIRLS point cadence: $cadence" }
        require(accumulation == EXPECTED_ACCUMULATION_MINUTES) {
            "Unexpected SWIRLS point accumulation: $accumulation"
        }
        require(unit == EXPECTED_UNIT) { "Unexpected SWIRLS point unit: $unit" }

        val locationObject = root.optJSONObject("location")
            ?: error("SWIRLS point location missing")
        val location = RainPointLocation(
            latitude = locationObject.requiredFiniteDouble("lat"),
            longitude = locationObject.requiredFiniteDouble("lon"),
        )
        if (expectedLatitude != null) requireClose(location.latitude, expectedLatitude, "latitude")
        if (expectedLongitude != null) requireClose(location.longitude, expectedLongitude, "longitude")

        val rawSamples = root.optJSONArray("samples")
            ?: error("SWIRLS point samples missing")
        val sampleCount = root.optInt("sampleCount", -1)
        require(sampleCount == EXPECTED_SAMPLE_COUNT) { "Unexpected SWIRLS point sample count: $sampleCount" }
        require(rawSamples.length() == EXPECTED_SAMPLE_COUNT) {
            "SWIRLS point samples expected $EXPECTED_SAMPLE_COUNT, got ${rawSamples.length()}"
        }
        val samples = buildList {
            for (index in 0 until rawSamples.length()) {
                val item = rawSamples.optJSONObject(index)
                    ?: error("SWIRLS point sample $index is not an object")
                val frameIndex = item.optInt("frameIndex", -1)
                val leadMinutes = item.optInt("leadMinutes", Int.MIN_VALUE)
                require(frameIndex == index) { "SWIRLS point frame index mismatch at $index" }
                require(leadMinutes == FIRST_LEAD_MINUTES + index * EXPECTED_CADENCE_MINUTES) {
                    "SWIRLS point lead mismatch at frame $index: $leadMinutes"
                }
                val amount = item.requiredFiniteDouble("accumulationMm")
                val spread = item.requiredFiniteDouble("spatialSpreadMm")
                require(amount >= 0.0) { "SWIRLS point accumulation must be non-negative" }
                require(spread >= 0.0) { "SWIRLS point spatial spread must be non-negative" }
                add(
                    RainSwirlsPointSample(
                        frameIndex = frameIndex,
                        validTime = item.requiredString("validTime"),
                        leadMinutes = leadMinutes,
                        windowStart = item.requiredString("windowStart"),
                        windowEnd = item.requiredString("windowEnd"),
                        accumulationMm = amount,
                        spatialSpreadMm = spread,
                    ),
                )
            }
        }

        val peakAccumulation = root.requiredFiniteDouble("peakAccumulationMm")
        val peakLead = root.optInt("peakLeadMinutes", Int.MIN_VALUE)
        require(peakAccumulation >= 0.0) { "SWIRLS point peak must be non-negative" }
        require(peakLead in FIRST_LEAD_MINUTES..LAST_LEAD_MINUTES) { "SWIRLS point peak lead invalid" }
        val firstWetLead = if (root.has("firstWetLeadMinutes") && !root.isNull("firstWetLeadMinutes")) {
            root.optInt("firstWetLeadMinutes", Int.MIN_VALUE).also {
                require(it in FIRST_LEAD_MINUTES..LAST_LEAD_MINUTES) { "SWIRLS first wet lead invalid" }
            }
        } else {
            null
        }

        return RainSwirlsPointSeries(
            workerVersion = version,
            runTime = runTime,
            cadenceMinutes = cadence,
            accumulationMinutes = accumulation,
            unit = unit,
            location = location,
            interpolation = root.requiredString("interpolation"),
            sampleCount = sampleCount,
            peakAccumulationMm = peakAccumulation,
            peakLeadMinutes = peakLead,
            firstWetLeadMinutes = firstWetLead,
            samples = samples,
        )
    }

    private fun JSONObject.requiredString(key: String): String =
        optString(key).takeIf { it.isNotBlank() }
            ?: error("Required field '$key' missing")

    private fun JSONObject.requiredFiniteDouble(key: String): Double {
        if (!has(key) || isNull(key)) error("Required numeric field '$key' missing")
        return optDouble(key, Double.NaN).takeIf { it.isFinite() }
            ?: error("Required numeric field '$key' invalid")
    }

    private fun requireClose(actual: Double, expected: Double, label: String) {
        require(abs(actual - expected) <= LOCATION_TOLERANCE_DEGREES) {
            "SWIRLS point $label mismatch: expected $expected, got $actual"
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 25_000
        private const val EXPECTED_SAMPLE_COUNT = 16
        private const val EXPECTED_CADENCE_MINUTES = 6
        private const val EXPECTED_ACCUMULATION_MINUTES = 30
        private const val FIRST_LEAD_MINUTES = 30
        private const val LAST_LEAD_MINUTES = 120
        private const val EXPECTED_UNIT = "mm / 30 min"
        private const val LOCATION_TOLERANCE_DEGREES = 0.00002
    }
}
