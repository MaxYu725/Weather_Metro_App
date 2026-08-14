package com.weather.metro.data.storm

import android.content.Context
import com.weather.metro.domain.storm.AgencyLiveResult
import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormLiveState
import com.weather.metro.domain.storm.StormPoint
import com.weather.metro.domain.storm.StormPointType
import com.weather.metro.domain.storm.StormTrack
import com.weather.metro.domain.storm.StormWindRadii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class CachedStormAgency(
    val result: AgencyLiveResult,
    val savedAtMillis: Long,
)

internal class StormLiveCache(context: Context) {
    private val directory = File(context.filesDir, CACHE_DIRECTORY)

    suspend fun loadAll(): Map<StormAgency, CachedStormAgency> = withContext(Dispatchers.IO) {
        buildMap {
            StormAgency.entries.forEach { agency ->
                val file = cacheFile(agency)
                if (!file.isFile) return@forEach
                val cached = runCatching { StormLiveCacheCodec.decode(file.readText()) }.getOrNull()
                    ?: return@forEach
                if (cached.result.agency == agency) put(agency, cached)
            }
        }
    }

    suspend fun save(result: AgencyLiveResult) = withContext(Dispatchers.IO) {
        require(result.state == StormLiveState.OK || result.state == StormLiveState.EMPTY) {
            "Only successful Storm live results may be cached"
        }
        if (!directory.exists()) directory.mkdirs()
        val target = cacheFile(result.agency)
        val temporary = File(directory, target.name + ".tmp")
        temporary.writeText(
            StormLiveCacheCodec.encode(
                CachedStormAgency(
                    result = result,
                    savedAtMillis = System.currentTimeMillis(),
                ),
            ),
        )
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        if (directory.exists()) directory.deleteRecursively()
    }

    private fun cacheFile(agency: StormAgency): File =
        File(directory, agency.name.lowercase() + ".json")

    companion object {
        private const val CACHE_DIRECTORY = "storm_live_cache_v1"
    }
}

internal object StormLiveCacheCodec {
    private const val VERSION = 1

    fun encode(cached: CachedStormAgency): String = JSONObject().apply {
        put("version", VERSION)
        put("savedAtMillis", cached.savedAtMillis)
        put("result", encodeResult(cached.result))
    }.toString()

    fun decode(payload: String): CachedStormAgency {
        val root = JSONObject(payload)
        require(root.optInt("version", -1) == VERSION) { "Unsupported Storm cache version" }
        val savedAtMillis = root.optLong("savedAtMillis", -1L)
        require(savedAtMillis >= 0L) { "Invalid Storm cache timestamp" }
        val result = decodeResult(root.optJSONObject("result") ?: error("Storm cache result missing"))
        require(result.state == StormLiveState.OK || result.state == StormLiveState.EMPTY) {
            "Storm cache contains a non-success state"
        }
        return CachedStormAgency(result = result, savedAtMillis = savedAtMillis)
    }

    private fun encodeResult(result: AgencyLiveResult): JSONObject = JSONObject().apply {
        put("agency", result.agency.name)
        put("state", result.state.name)
        putNullable("message", result.message)
        putNullable("updatedAt", result.updatedAt)
        put("storms", JSONArray().apply { result.storms.forEach { put(encodeTrack(it)) } })
    }

    private fun decodeResult(value: JSONObject): AgencyLiveResult = AgencyLiveResult(
        agency = StormAgency.fromWire(value.requiredString("agency")),
        state = runCatching { StormLiveState.valueOf(value.requiredString("state")) }
            .getOrElse { error("Unsupported Storm cache state") },
        message = value.optNullableString("message"),
        updatedAt = value.optNullableString("updatedAt"),
        storms = value.optJSONArray("storms").mapObjects(::decodeTrack),
    )

    private fun encodeTrack(track: StormTrack): JSONObject = JSONObject().apply {
        put("stableKey", track.stableKey)
        put("agency", track.agency.name)
        put("agencyStormId", track.agencyStormId)
        putNullable("internationalNumber", track.internationalNumber)
        putNullable("nameEn", track.nameEn)
        putNullable("nameZh", track.nameZh)
        putNullable("bulletinTime", track.bulletinTime)
        put("analysisPoints", JSONArray().apply { track.analysisPoints.forEach { put(encodePoint(it)) } })
        put("forecastPoints", JSONArray().apply { track.forecastPoints.forEach { put(encodePoint(it)) } })
    }

    private fun decodeTrack(value: JSONObject): StormTrack {
        val agency = StormAgency.fromWire(value.requiredString("agency"))
        return StormTrack(
            stableKey = value.requiredString("stableKey"),
            agency = agency,
            agencyStormId = value.requiredString("agencyStormId"),
            internationalNumber = value.optNullableString("internationalNumber"),
            nameEn = value.optNullableString("nameEn"),
            nameZh = value.optNullableString("nameZh"),
            bulletinTime = value.optNullableString("bulletinTime"),
            analysisPoints = value.optJSONArray("analysisPoints").mapObjects(::decodePoint),
            forecastPoints = value.optJSONArray("forecastPoints").mapObjects(::decodePoint),
        ).also {
            require(it.analysisPoints.all { point -> point.pointType == StormPointType.ANALYSIS }) {
                "Storm cache analysis point type mismatch"
            }
            require(it.forecastPoints.all { point -> point.pointType == StormPointType.FORECAST }) {
                "Storm cache forecast point type mismatch"
            }
        }
    }

    private fun encodePoint(point: StormPoint): JSONObject = JSONObject().apply {
        put("validAt", point.validAt)
        put("latitude", point.latitude)
        put("longitude", point.longitude)
        put("pointType", point.pointType.wireValue)
        putNullable("intensityLabel", point.intensityLabel)
        putNullable("intensityCode", point.intensityCode)
        putNullable("windSpeedMs", point.windSpeedMs)
        putNullable("pressureHpa", point.pressureHpa)
        putNullable("forecastHour", point.forecastHour)
        putNullable("probabilityRadiusKm", point.probabilityRadiusKm)
        putNullable("maximumGustMs", point.maximumGustMs)
        putNullable("movingSpeedKmh", point.movingSpeedKmh)
        putNullable("movingDirection", point.movingDirection)
        putNullable("movementPrediction", point.movementPrediction)
        putNullable("stateTransfer", point.stateTransfer)
        put("windRadii", JSONArray().apply { point.windRadii.forEach { put(encodeWindRadii(it)) } })
    }

    private fun decodePoint(value: JSONObject): StormPoint {
        val latitude = value.requiredFiniteDouble("latitude")
        val longitude = value.requiredFiniteDouble("longitude")
        require(latitude in -90.0..90.0) { "Invalid cached Storm latitude" }
        require(longitude in -180.0..180.0) { "Invalid cached Storm longitude" }
        return StormPoint(
            validAt = value.requiredString("validAt"),
            latitude = latitude,
            longitude = longitude,
            pointType = StormPointType.fromWire(value.requiredString("pointType")),
            intensityLabel = value.optNullableString("intensityLabel"),
            intensityCode = value.optNullableString("intensityCode"),
            windSpeedMs = value.optNullableDouble("windSpeedMs"),
            pressureHpa = value.optNullableDouble("pressureHpa"),
            forecastHour = value.optNullableInt("forecastHour"),
            probabilityRadiusKm = value.optNullableDouble("probabilityRadiusKm"),
            maximumGustMs = value.optNullableDouble("maximumGustMs"),
            movingSpeedKmh = value.optNullableDouble("movingSpeedKmh"),
            movingDirection = value.optNullableString("movingDirection"),
            movementPrediction = value.optNullableString("movementPrediction"),
            stateTransfer = value.optNullableString("stateTransfer"),
            windRadii = value.optJSONArray("windRadii").mapObjects(::decodeWindRadii),
        )
    }

    private fun encodeWindRadii(value: StormWindRadii): JSONObject = JSONObject().apply {
        putNullable("level", value.level)
        put("northEastKm", value.northEastKm)
        put("southEastKm", value.southEastKm)
        put("southWestKm", value.southWestKm)
        put("northWestKm", value.northWestKm)
    }

    private fun decodeWindRadii(value: JSONObject): StormWindRadii = StormWindRadii(
        level = value.optNullableString("level"),
        northEastKm = value.requiredFiniteDouble("northEastKm"),
        southEastKm = value.requiredFiniteDouble("southEastKm"),
        southWestKm = value.requiredFiniteDouble("southWestKm"),
        northWestKm = value.requiredFiniteDouble("northWestKm"),
    )

    private fun JSONObject.requiredString(key: String): String =
        optString(key).takeIf { it.isNotBlank() && it != "null" }
            ?: error("Required Storm cache field '$key' missing")

    private fun JSONObject.requiredFiniteDouble(key: String): Double {
        if (!has(key) || isNull(key)) error("Required Storm cache number '$key' missing")
        val value = optDouble(key, Double.NaN)
        require(value.isFinite()) { "Invalid Storm cache number '$key'" }
        return value
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it != "null" }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key, Double.NaN).takeIf { it.isFinite() }
    }

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)

    private fun JSONObject.putNullable(key: String, value: Any?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        val array = this ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optJSONObject(index) ?: error("Storm cache array item $index is invalid")
                add(transform(value))
            }
        }
    }
}
