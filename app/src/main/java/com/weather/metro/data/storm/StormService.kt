package com.weather.metro.data.storm

import com.weather.metro.data.tools.ToolEndpoints
import com.weather.metro.domain.storm.AgencyLiveResult
import com.weather.metro.domain.storm.ArchiveAdvisoryDetail
import com.weather.metro.domain.storm.ArchiveAdvisorySummary
import com.weather.metro.domain.storm.ArchiveStorm
import com.weather.metro.domain.storm.ArchiveStormDetail
import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormHealth
import com.weather.metro.domain.storm.StormPoint
import com.weather.metro.domain.storm.StormPointType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

internal interface StormHttpTransport {
    suspend fun getText(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): String
}

internal class UrlConnectionStormTransport : StormHttpTransport {
    override suspend fun getText(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): String = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "WeatherMetroApp/1.1 StormModule")
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code from ${URI(url).host}")
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

data class StormNetworkResult<T>(
    val value: T,
    val rawPayload: String,
)

/**
 * Native boundary for documented public Storm Worker APIs.
 *
 * Live loading is source-scoped and independent: one HKO/CMA/JMA/CWA failure never blocks
 * another agency. Arbitrary proxy construction, Cloudflare credentials and admin routes do
 * not exist on this boundary.
 */
class StormService internal constructor(
    private val transport: StormHttpTransport = UrlConnectionStormTransport(),
    private val liveLoader: StormLiveLoader = StormLiveLoader(),
) {
    suspend fun loadLive(force: Boolean = false): List<AgencyLiveResult> {
        if (force) Unit // Transport is no-store; retained for the stable service contract.
        return liveLoader.loadAll()
    }

    suspend fun loadLiveAgency(
        agency: StormAgency,
        force: Boolean = false,
    ): AgencyLiveResult {
        if (force) Unit
        return liveLoader.loadAgency(agency)
    }

    suspend fun probeHealth(): StormNetworkResult<StormHealth> = load(
        url = ToolEndpoints.stormHealth(),
        parser = ::parseHealth,
    )

    suspend fun loadHistory(limit: Int = 100): StormNetworkResult<List<ArchiveStorm>> = load(
        url = ToolEndpoints.stormHistoryStorms(limit),
        parser = ::parseHistoryStorms,
    )

    suspend fun loadStorm(stormId: String): StormNetworkResult<ArchiveStormDetail> = load(
        url = ToolEndpoints.stormHistoryStorm(stormId),
        parser = ::parseStorm,
    )

    suspend fun loadAdvisories(
        stormId: String,
        limit: Int = 200,
    ): StormNetworkResult<List<ArchiveAdvisorySummary>> = load(
        url = ToolEndpoints.stormHistoryAdvisories(stormId, limit),
        parser = { payload -> parseAdvisories(payload, expectedStormId = stormId) },
    )

    suspend fun loadAdvisory(advisoryId: String): StormNetworkResult<ArchiveAdvisoryDetail> = load(
        url = ToolEndpoints.stormHistoryAdvisory(advisoryId),
        parser = ::parseAdvisory,
    )

    fun parseHealth(payload: String): StormHealth {
        val root = JSONObject(payload)
        requireNotExplicitFailure(root, "Storm health")
        val ok = when {
            root.has("ok") -> root.optBoolean("ok", false)
            root.optString("status").equals("ok", ignoreCase = true) -> true
            else -> true
        }
        return StormHealth(
            ok = ok,
            version = root.firstNonBlank("version", "workerVersion"),
            checkedAt = root.firstNonBlank("checkedAt", "time", "timestamp", "generatedAt"),
        )
    }

    fun parseHistoryStorms(payload: String): List<ArchiveStorm> {
        val root = JSONObject(payload)
        requireNotExplicitFailure(root, "Storm history")
        val storms = root.optJSONArray("storms") ?: error("Storm history list missing")
        return storms.mapObjects(::parseStormObject)
    }

    fun parseStorm(payload: String): ArchiveStormDetail {
        val root = JSONObject(payload)
        requireNotExplicitFailure(root, "Storm history detail")
        val storm = root.optJSONObject("storm") ?: error("Storm history detail missing")
        return ArchiveStormDetail(parseStormObject(storm))
    }

    fun parseAdvisories(
        payload: String,
        expectedStormId: String? = null,
    ): List<ArchiveAdvisorySummary> {
        val root = JSONObject(payload)
        requireNotExplicitFailure(root, "Storm advisory list")
        val advisories = root.optJSONArray("advisories") ?: error("Storm advisory list missing")
        return advisories.mapObjects { value ->
            parseAdvisorySummary(value, expectedStormId = expectedStormId)
        }
    }

    fun parseAdvisory(payload: String): ArchiveAdvisoryDetail {
        val root = JSONObject(payload)
        requireNotExplicitFailure(root, "Storm advisory detail")
        val advisoryObject = root.optJSONObject("advisory") ?: error("Storm advisory detail missing")
        val pointsArray = root.optJSONArray("points") ?: error("Storm advisory points missing")
        val points = pointsArray.mapObjects(::parsePoint)
        val advisory = parseAdvisorySummary(
            value = advisoryObject,
            expectedStormId = null,
            fallbackPointCount = points.size,
        )
        return ArchiveAdvisoryDetail(advisory = advisory, points = points)
    }

    private suspend fun <T> load(
        url: String,
        parser: (String) -> T,
    ): StormNetworkResult<T> {
        val payload = transport.getText(
            url = url,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = HISTORY_READ_TIMEOUT_MS,
        )
        return StormNetworkResult(value = parser(payload), rawPayload = payload)
    }

    private fun parseStormObject(value: JSONObject): ArchiveStorm {
        val id = value.requiredString("id")
        val year = value.optInt("year", -1)
        require(year in 1900..2200) { "Invalid Storm history year for $id" }
        val advisoryCount = value.optInt("advisory_count", 0)
        require(advisoryCount >= 0) { "Invalid Storm advisory count for $id" }
        return ArchiveStorm(
            id = id,
            year = year,
            internationalNumber = value.optNonBlankString("international_number"),
            nameEn = value.optNonBlankString("name_en"),
            nameZh = value.optNonBlankString("name_zh"),
            status = value.optNonBlankString("status"),
            firstSeenAt = value.optNonBlankString("first_seen_at"),
            lastSeenAt = value.optNonBlankString("last_seen_at"),
            advisoryCount = advisoryCount,
        )
    }

    private fun parseAdvisorySummary(
        value: JSONObject,
        expectedStormId: String?,
        fallbackPointCount: Int = 0,
    ): ArchiveAdvisorySummary {
        val id = value.requiredString("id")
        val stormId = value.optNonBlankString("storm_id")
            ?: expectedStormId
            ?: error("Storm advisory $id storm id missing")
        val pointCount = if (value.has("point_count") && !value.isNull("point_count")) {
            value.optInt("point_count", -1)
        } else {
            fallbackPointCount
        }
        require(pointCount >= 0) { "Invalid point count for Storm advisory $id" }
        return ArchiveAdvisorySummary(
            id = id,
            stormId = stormId,
            agency = StormAgency.fromWire(value.requiredString("agency")),
            issuedAt = value.requiredString("issued_at"),
            pointCount = pointCount,
            parserVersion = value.optNonBlankString("parser_version"),
            sourceCode = value.optNonBlankString("source_code"),
        )
    }

    private fun parsePoint(value: JSONObject): StormPoint {
        val latitude = value.requiredFiniteDouble("latitude")
        val longitude = value.requiredFiniteDouble("longitude")
        require(latitude in -90.0..90.0) { "Invalid Storm point latitude" }
        require(longitude in -180.0..180.0) { "Invalid Storm point longitude" }
        return StormPoint(
            validAt = value.requiredString("valid_at"),
            latitude = latitude,
            longitude = longitude,
            pointType = StormPointType.fromWire(value.requiredString("point_type")),
            intensityLabel = value.optNonBlankString("intensity_label"),
            intensityCode = value.optNonBlankString("intensity_code"),
            windSpeedMs = value.optFiniteDouble("wind_ms"),
            pressureHpa = value.optFiniteDouble("pressure_hpa"),
            forecastHour = value.optNullableInt("forecast_hour"),
            probabilityRadiusKm = value.optFiniteDouble("probability_radius_km"),
        )
    }

    private fun requireNotExplicitFailure(root: JSONObject, label: String) {
        if (root.has("ok") && !root.optBoolean("ok", false)) {
            error("$label failed: ${root.optString("error").ifBlank { "unknown Worker error" }}")
        }
    }

    private fun JSONObject.requiredString(key: String): String =
        optString(key).takeIf { it.isNotBlank() && it != "null" }
            ?: error("Required Storm field '$key' missing")

    private fun JSONObject.optNonBlankString(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.firstNonBlank(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> optNonBlankString(key) }

    private fun JSONObject.requiredFiniteDouble(key: String): Double {
        if (!has(key) || isNull(key)) error("Required Storm numeric field '$key' missing")
        val number = optDouble(key, Double.NaN)
        require(number.isFinite()) { "Required Storm numeric field '$key' invalid" }
        return number
    }

    private fun JSONObject.optFiniteDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val number = optDouble(key, Double.NaN)
        return number.takeIf { it.isFinite() }
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key)
    }

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> = buildList {
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: error("Storm array item $index is not an object")
            add(transform(value))
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val HISTORY_READ_TIMEOUT_MS = 16_000
    }
}
