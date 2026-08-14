package com.weather.metro.data.rain

import com.weather.metro.data.tools.ToolEndpoints
import com.weather.metro.domain.rain.RainCapabilities
import com.weather.metro.domain.rain.RainDataQuality
import com.weather.metro.domain.rain.RainFreshness
import com.weather.metro.domain.rain.RainGridCoverage
import com.weather.metro.domain.rain.RainPeriod
import com.weather.metro.domain.rain.RainPointForecast
import com.weather.metro.domain.rain.RainPointLocation
import com.weather.metro.domain.rain.RainPointSummary
import com.weather.metro.domain.rain.RainSpatialQuality
import com.weather.metro.domain.rain.SwirlsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlin.math.abs

data class RainNetworkResult<T>(
    val value: T,
    val rawPayload: String,
)

internal interface RainHttpTransport {
    suspend fun get(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): String
}

internal class UrlConnectionRainTransport : RainHttpTransport {
    override suspend fun get(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): String = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "WeatherMetroApp/1.1 RainModule")
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code from ${URI(url).host}")
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

class RainTrackClient internal constructor(
    private val transport: RainHttpTransport = UrlConnectionRainTransport(),
) {
    suspend fun loadCapabilities(): RainNetworkResult<RainCapabilities> {
        val payload = transport.get(
            ToolEndpoints.rainCapabilities(),
            connectTimeoutMs = STANDARD_CONNECT_TIMEOUT_MS,
            readTimeoutMs = STANDARD_READ_TIMEOUT_MS,
        )
        return RainNetworkResult(RainParsers.parseCapabilities(payload), payload)
    }

    suspend fun loadPointForecast(
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
    ): RainNetworkResult<RainPointForecast> {
        val payload = transport.get(
            ToolEndpoints.rainPoint(latitude, longitude, radiusKm),
            connectTimeoutMs = STANDARD_CONNECT_TIMEOUT_MS,
            readTimeoutMs = STANDARD_READ_TIMEOUT_MS,
        )
        return RainNetworkResult(
            RainParsers.parsePointForecast(
                payload = payload,
                expectedLatitude = latitude,
                expectedLongitude = longitude,
                expectedRadiusKm = radiusKm,
            ),
            payload,
        )
    }

    fun parseCapabilities(payload: String): RainCapabilities = RainParsers.parseCapabilities(payload)

    fun parsePointForecast(
        payload: String,
        expectedLatitude: Double? = null,
        expectedLongitude: Double? = null,
        expectedRadiusKm: Int? = null,
    ): RainPointForecast = RainParsers.parsePointForecast(
        payload,
        expectedLatitude,
        expectedLongitude,
        expectedRadiusKm,
    )

    companion object {
        private const val STANDARD_CONNECT_TIMEOUT_MS = 10_000
        private const val STANDARD_READ_TIMEOUT_MS = 20_000
    }
}

internal object RainParsers {
    private const val RAIN_UNIT = "mm / 30 min"

    fun parseCapabilities(payload: String): RainCapabilities {
        val root = JSONObject(payload)
        requireOk(root, "Rain capabilities")
        val version = requiredString(root, "version")
        val capabilities = root.optJSONObject("capabilities")
            ?: error("Rain capabilities object missing")

        val swirlsFrames = capabilities.optBoolean("swirlsFrames", false)
        val contractObject = root.optJSONObject("swirlsContract")
            ?: capabilities.optJSONObject("swirls")
        val swirlsContract = contractObject?.let(::parseSwirlsContract)
        if (swirlsFrames) require(swirlsContract != null) { "SWIRLS enabled without contract" }

        return RainCapabilities(
            workerVersion = version,
            pointForecast = capabilities.optBoolean("pointForecast", false),
            nowcast = capabilities.optBoolean("nowcastGrid", false),
            radarFrames = capabilities.optBoolean("radarFrames", false),
            swirlsFrames = swirlsFrames,
            swirlsContract = swirlsContract,
        )
    }

    fun parsePointForecast(
        payload: String,
        expectedLatitude: Double? = null,
        expectedLongitude: Double? = null,
        expectedRadiusKm: Int? = null,
    ): RainPointForecast {
        val root = JSONObject(payload)
        requireOk(root, "Rain point forecast")
        val version = requiredString(root, "version")
        val unit = requiredString(root, "unit")
        require(unit == RAIN_UNIT) { "Unexpected rainfall unit: $unit" }

        val locationObject = root.optJSONObject("location")
        val location = locationObject?.let {
            RainPointLocation(
                latitude = it.requiredFiniteDouble("lat"),
                longitude = it.requiredFiniteDouble("lon"),
            )
        }
        if (expectedLatitude != null && expectedLongitude != null) {
            require(location != null) { "Rain point response location missing" }
            requireClose(location.latitude, expectedLatitude, "latitude")
            requireClose(location.longitude, expectedLongitude, "longitude")
        }

        val nearbyRadius = root.optFiniteDouble("nearbyRadiusKm")
        if (nearbyRadius != null) require(nearbyRadius > 0.0) { "Rain point nearby radius must be positive" }
        if (expectedRadiusKm != null) {
            require(nearbyRadius != null) { "Rain point nearby radius missing" }
            requireClose(nearbyRadius, expectedRadiusKm.toDouble(), "nearby radius")
        }

        val grid = root.optJSONObject("grid")?.let(::parseCoverage)
        val rawPeriods = root.optJSONArray("periods")
            ?: error("Rain point forecast periods missing")
        require(rawPeriods.length() > 0) { "Rain point forecast periods empty" }
        val periods = buildList {
            for (index in 0 until rawPeriods.length()) {
                val item = rawPeriods.optJSONObject(index)
                    ?: error("Rain point period $index is not an object")
                val amount = item.requiredFiniteDouble("amountMm")
                val nearbyMax = item.requiredFiniteDouble("nearbyMaxMm")
                require(amount >= 0.0) { "Rain point amount must be non-negative" }
                require(nearbyMax >= 0.0) { "Rain point nearby maximum must be non-negative" }
                val nearbyMean = item.optFiniteDouble("nearbyMeanMm")
                val nearestGrid = item.optFiniteDouble("nearestGridKm")
                val spatialSpread = item.optFiniteDouble("spatialSpreadMm")
                if (nearbyMean != null) require(nearbyMean >= 0.0) { "Rain point nearby mean must be non-negative" }
                if (nearestGrid != null) require(nearestGrid >= 0.0) { "Nearest grid distance must be non-negative" }
                if (spatialSpread != null) require(spatialSpread >= 0.0) { "Spatial spread must be non-negative" }
                add(
                    RainPeriod(
                        time = requiredString(item, "time"),
                        leadMinutes = item.optIntOrNull("leadMinutes"),
                        amountMm = amount,
                        nearbyMaxMm = nearbyMax,
                        nearbyMeanMm = nearbyMean,
                        nearestGridKm = nearestGrid,
                        spatialSpreadMm = spatialSpread,
                        level = item.optNonBlankString("level"),
                    ),
                )
            }
        }

        return RainPointForecast(
            workerVersion = version,
            unit = unit,
            sourceUpdatedAt = root.optNonBlankString("sourceUpdatedAt"),
            issueTime = root.optNonBlankString("issueTime"),
            generatedAt = root.optNonBlankString("generatedAt"),
            location = location,
            nearbyRadiusKm = nearbyRadius,
            interpolation = root.optNonBlankString("interpolation"),
            grid = grid,
            summary = root.optJSONObject("summary")?.let(::parseSummary),
            periods = periods,
            quality = root.optJSONObject("dataQuality")?.let(::parseQuality),
        )
    }

    private fun parseSwirlsContract(value: JSONObject): SwirlsContract {
        val frameCount = value.optInt("frameCount", -1)
        val cadence = value.optInt("cadenceMinutes", -1)
        val accumulation = value.optInt("accumulationMinutes", -1)
        require(frameCount > 0) { "Invalid SWIRLS frame count" }
        require(cadence > 0) { "Invalid SWIRLS cadence" }
        require(accumulation > 0) { "Invalid SWIRLS accumulation" }
        return SwirlsContract(frameCount, cadence, accumulation)
    }

    private fun parseCoverage(value: JSONObject): RainGridCoverage {
        val minLat = value.requiredFiniteDouble("minLat")
        val maxLat = value.requiredFiniteDouble("maxLat")
        val minLon = value.requiredFiniteDouble("minLon")
        val maxLon = value.requiredFiniteDouble("maxLon")
        require(maxLat > minLat) { "Rain grid latitude coverage invalid" }
        require(maxLon > minLon) { "Rain grid longitude coverage invalid" }
        return RainGridCoverage(minLat, maxLat, minLon, maxLon)
    }

    private fun parseSummary(value: JSONObject): RainPointSummary = RainPointSummary(
        text = value.optNonBlankString("text"),
        totalMm = value.optFiniteDouble("totalMm")?.also { require(it >= 0.0) },
        peakMm = value.optFiniteDouble("peakMm")?.also { require(it >= 0.0) },
        peakTime = value.optNonBlankString("peakTime"),
        peakWindowStart = value.optNonBlankString("peakWindowStart"),
        peakWindowEnd = value.optNonBlankString("peakWindowEnd"),
        rainStartTime = value.optNonBlankString("rainStartTime"),
        rainStartWindowStart = value.optNonBlankString("rainStartWindowStart"),
        rainStartWindowEnd = value.optNonBlankString("rainStartWindowEnd"),
        rainStartLeadMinutes = value.optIntOrNull("rainStartLeadMinutes"),
        rainEndTime = value.optNonBlankString("rainEndTime"),
        rainEndWindowStart = value.optNonBlankString("rainEndWindowStart"),
        rainEndWindowEnd = value.optNonBlankString("rainEndWindowEnd"),
        wetPeriodCount = value.optIntOrNull("wetPeriodCount"),
    )

    private fun parseQuality(value: JSONObject): RainDataQuality {
        val freshness = value.optJSONObject("freshness")?.let {
            RainFreshness(
                status = requiredString(it, "status"),
                label = it.optNonBlankString("label"),
                note = it.optNonBlankString("note"),
                sourceAgeMinutes = it.optFiniteDouble("sourceAgeMinutes"),
            )
        }
        val spatial = value.optJSONObject("spatial")?.let {
            RainSpatialQuality(
                status = requiredString(it, "status"),
                label = it.optNonBlankString("label"),
                note = it.optNonBlankString("note"),
                nearbyDeltaMaxMm = it.optFiniteDouble("nearbyDeltaMaxMm"),
                maxSpatialSpreadMm = it.optFiniteDouble("maxSpatialSpreadMm"),
            )
        }
        return RainDataQuality(freshness, spatial)
    }

    private fun requireOk(root: JSONObject, label: String) {
        require(root.optBoolean("ok", false)) {
            "$label failed: ${root.optString("error").ifBlank { "unknown Worker error" }}"
        }
    }

    private fun requiredString(value: JSONObject, key: String): String =
        value.optString(key).takeIf { it.isNotBlank() }
            ?: error("Required field '$key' missing")

    private fun JSONObject.optNonBlankString(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.requiredFiniteDouble(key: String): Double =
        optFiniteDouble(key) ?: error("Required numeric field '$key' missing")

    private fun JSONObject.optFiniteDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val number = optDouble(key, Double.NaN)
        return number.takeIf { it.isFinite() }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val value = optInt(key, Int.MIN_VALUE)
        return value.takeIf { it != Int.MIN_VALUE }
    }

    private fun requireClose(actual: Double, expected: Double, label: String) {
        require(abs(actual - expected) <= 0.000001) {
            "Rain point $label mismatch: expected $expected, got $actual"
        }
    }
}
