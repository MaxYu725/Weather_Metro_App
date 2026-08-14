package com.weather.metro.data.rain

import com.weather.metro.data.tools.RainRadarMode
import com.weather.metro.data.tools.ToolEndpoints
import com.weather.metro.domain.rain.RainRadarBounds
import com.weather.metro.domain.rain.RainRadarContract
import com.weather.metro.domain.rain.RainRadarFrame
import com.weather.metro.domain.rain.RainRadarTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

internal interface RainRadarHttpTransport {
    suspend fun getText(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): String

    suspend fun getBytes(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): ByteArray
}

internal class UrlConnectionRainRadarTransport : RainRadarHttpTransport {
    override suspend fun getText(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): String = withContext(Dispatchers.IO) {
        open(url, connectTimeoutMs, readTimeoutMs, "application/json").use { connection ->
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }
    }

    override suspend fun getBytes(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): ByteArray = withContext(Dispatchers.IO) {
        open(url, connectTimeoutMs, readTimeoutMs, "image/avif,image/webp,image/png,image/jpeg,image/gif,image/svg+xml,*/*").use { connection ->
            val contentType = connection.contentType.orEmpty().lowercase()
            require(contentType.startsWith("image/")) { "Radar Worker returned non-image content" }
            connection.inputStream.use { it.readBytes() }
        }
    }

    private fun open(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        accept: String,
    ): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", "WeatherMetroApp/1.1 RainRadarModule")
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            error("HTTP $code from ${URI(url).host}")
        }
        return connection
    }
}

class RainRadarClient internal constructor(
    private val transport: RainRadarHttpTransport = UrlConnectionRainRadarTransport(),
) {
    suspend fun loadContract(): RainNetworkResult<RainRadarContract> {
        val payload = transport.getText(
            ToolEndpoints.rainCapabilities(),
            connectTimeoutMs = STANDARD_CONNECT_TIMEOUT_MS,
            readTimeoutMs = STANDARD_READ_TIMEOUT_MS,
        )
        return RainNetworkResult(parseContract(payload), payload)
    }

    suspend fun loadFrames(
        rangeKm: Int,
        heightKm: Int,
        mode: RainRadarMode = RainRadarMode.LIVE,
    ): RainNetworkResult<RainRadarTimeline> {
        val payload = transport.getText(
            ToolEndpoints.rainRadarFrames(rangeKm, heightKm, mode),
            connectTimeoutMs = STANDARD_CONNECT_TIMEOUT_MS,
            readTimeoutMs = STANDARD_READ_TIMEOUT_MS,
        )
        return RainNetworkResult(
            parseFrames(
                payload = payload,
                expectedRangeKm = rangeKm,
                expectedHeightKm = heightKm,
                expectedMode = mode,
            ),
            payload,
        )
    }

    suspend fun loadImage(relativePath: String): ByteArray = transport.getBytes(
        ToolEndpoints.rainRadarImage(relativePath),
        connectTimeoutMs = STANDARD_CONNECT_TIMEOUT_MS,
        readTimeoutMs = RADAR_IMAGE_READ_TIMEOUT_MS,
    ).also { require(it.isNotEmpty()) { "Radar image is empty" } }

    fun resolveImageUrl(relativePath: String): String = ToolEndpoints.rainRadarImage(relativePath)

    fun parseContract(payload: String): RainRadarContract {
        val root = JSONObject(payload)
        requireOk(root, "Rain radar capabilities")
        val capabilities = root.optJSONObject("capabilities")
            ?: error("Rain capabilities object missing")
        require(capabilities.optBoolean("radarFrames", false)) { "Radar frames capability is disabled" }
        val value = root.optJSONObject("radarContract")
            ?: capabilities.optJSONObject("radar")
            ?: error("Radar contract missing")

        val version = requiredString(value, "version")
        require(version == RADAR_CONTRACT_VERSION) { "Unsupported radar contract version $version" }
        require(value.optBoolean("enabled", true)) { "Radar contract is disabled" }

        val ranges = value.optJSONArray("rangesKm")
            ?: error("Radar ranges missing")
        val rangesKm = buildList {
            for (index in 0 until ranges.length()) {
                val range = ranges.optInt(index, -1)
                require(range > 0) { "Invalid radar range" }
                add(range)
            }
        }.distinct()
        require(rangesKm.isNotEmpty()) { "Radar ranges empty" }

        val heightsObject = value.optJSONObject("heightsKmByRange")
            ?: error("Radar height contract missing")
        val heightsKmByRange = rangesKm.associateWith { range ->
            val raw = heightsObject.optJSONArray(range.toString())
                ?: error("Radar heights missing for range $range")
            buildList {
                for (index in 0 until raw.length()) {
                    val height = raw.optInt(index, -1)
                    require(height > 0) { "Invalid radar height" }
                    add(height)
                }
            }.distinct().also { require(it.isNotEmpty()) { "Radar heights empty for range $range" } }
        }

        val modesArray = value.optJSONArray("modes") ?: error("Radar modes missing")
        val modes = buildList {
            for (index in 0 until modesArray.length()) {
                val mode = modesArray.optString(index).trim().lowercase()
                require(mode == RainRadarMode.LIVE.wireValue || mode == RainRadarMode.TEST.wireValue) {
                    "Unsupported radar mode $mode"
                }
                add(mode)
            }
        }.distinct()
        require(modes.isNotEmpty()) { "Radar modes empty" }

        val defaultHeightKm = value.optInt("defaultHeightKm", -1)
        require(defaultHeightKm > 0) { "Invalid default radar height" }
        require(heightsKmByRange.values.any { defaultHeightKm in it }) {
            "Default radar height is not supported by any range"
        }
        val cadenceMinutes = value.optInt("cadenceMinutes", -1)
        val maxFrames = value.optInt("maxFrames", -1)
        require(cadenceMinutes > 0) { "Invalid radar cadence" }
        require(maxFrames > 0) { "Invalid radar frame limit" }

        return RainRadarContract(
            version = version,
            rangesKm = rangesKm,
            heightsKmByRange = heightsKmByRange,
            defaultHeightKm = defaultHeightKm,
            modes = modes,
            cadenceMinutes = cadenceMinutes,
            maxFrames = maxFrames,
        )
    }

    fun parseFrames(
        payload: String,
        expectedRangeKm: Int? = null,
        expectedHeightKm: Int? = null,
        expectedMode: RainRadarMode? = null,
    ): RainRadarTimeline {
        val root = JSONObject(payload)
        requireOk(root, "Rain radar frames")
        val workerVersion = requiredString(root, "version")
        val contractVersion = requiredString(root, "contractVersion")
        require(contractVersion == RADAR_CONTRACT_VERSION) {
            "Unsupported radar contract version $contractVersion"
        }

        val rangeKm = root.optInt("rangeKm", -1)
        val heightKm = root.optInt("heightKm", -1)
        require(rangeKm > 0) { "Invalid radar response range" }
        require(heightKm > 0) { "Invalid radar response height" }
        if (expectedRangeKm != null) require(rangeKm == expectedRangeKm) { "Radar range mismatch" }
        if (expectedHeightKm != null) require(heightKm == expectedHeightKm) { "Radar height mismatch" }

        val mode = requiredString(root, "mode").lowercase()
        require(mode == RainRadarMode.LIVE.wireValue || mode == RainRadarMode.TEST.wireValue) {
            "Unsupported radar response mode $mode"
        }
        if (expectedMode != null) require(mode == expectedMode.wireValue) { "Radar mode mismatch" }

        val rawFrames = root.optJSONArray("frames") ?: error("Radar frames missing")
        require(rawFrames.length() > 0) { "Radar frames empty" }
        val declaredFrameCount = root.optInt("frameCount", rawFrames.length())
        require(declaredFrameCount == rawFrames.length()) { "Radar frame count mismatch" }

        val frames = buildList {
            for (index in 0 until rawFrames.length()) {
                val frame = rawFrames.optJSONObject(index)
                    ?: error("Radar frame $index is not an object")
                val imageUrl = requiredString(frame, "imageUrl")
                ToolEndpoints.rainRadarImage(imageUrl)
                val bounds = frame.optJSONObject("bounds") ?: error("Radar frame $index bounds missing")
                val north = bounds.requiredFiniteDouble("north")
                val south = bounds.requiredFiniteDouble("south")
                val east = bounds.requiredFiniteDouble("east")
                val west = bounds.requiredFiniteDouble("west")
                require(north > south) { "Radar frame $index latitude bounds invalid" }
                require(east > west) { "Radar frame $index longitude bounds invalid" }
                add(
                    RainRadarFrame(
                        id = requiredString(frame, "id"),
                        time = requiredString(frame, "time"),
                        imageUrl = imageUrl,
                        bounds = RainRadarBounds(
                            north = north,
                            south = south,
                            east = east,
                            west = west,
                        ),
                    ),
                )
            }
        }

        val cadenceMinutes = root.optInt("cadenceMinutes", -1).takeIf { it > 0 }
        return RainRadarTimeline(
            workerVersion = workerVersion,
            contractVersion = contractVersion,
            rangeKm = rangeKm,
            heightKm = heightKm,
            mode = mode,
            issueTime = root.optNonBlankString("issueTime"),
            cadenceMinutes = cadenceMinutes,
            renderMode = root.optNonBlankString("renderMode"),
            frames = frames,
        )
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

    private fun JSONObject.requiredFiniteDouble(key: String): Double {
        if (!has(key) || isNull(key)) error("Required numeric field '$key' missing")
        val number = optDouble(key, Double.NaN)
        require(number.isFinite()) { "Required numeric field '$key' invalid" }
        return number
    }

    companion object {
        private const val RADAR_CONTRACT_VERSION = "1.0"
        private const val STANDARD_CONNECT_TIMEOUT_MS = 10_000
        private const val STANDARD_READ_TIMEOUT_MS = 20_000
        private const val RADAR_IMAGE_READ_TIMEOUT_MS = 30_000
    }
}
