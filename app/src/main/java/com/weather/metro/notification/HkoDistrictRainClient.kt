package com.weather.metro.notification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

internal data class HkoDistrictRainObservation(
    val district: String,
    val pastHourMinMm: Double?,
    val pastHourMaxMm: Double?,
    val observedAt: String,
)

/** Lightweight HKO reader used only by the location heavy-rain worker. */
internal class HkoDistrictRainClient {
    suspend fun load(district: String): HkoDistrictRainObservation = withContext(Dispatchers.IO) {
        val connection = URI(HKO_RHRREAD_URL).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "WeatherMetroApp/1.0 (Android)")
            val code = connection.responseCode
            if (code !in 200..299) error("HKO district rainfall HTTP $code")
            val text = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            parse(text, district)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(payload: String, district: String): HkoDistrictRainObservation {
        val root = JSONObject(payload)
        val rainfall = root.optJSONObject("rainfall")
        val rows = rainfall?.optJSONArray("data")
        var minMm: Double? = null
        var maxMm: Double? = null
        if (rows != null) {
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                if (item.optString("place") != district) continue
                minMm = item.optNullableDouble("min")
                maxMm = item.optNullableDouble("max")
                break
            }
        }
        return HkoDistrictRainObservation(
            district = district,
            pastHourMinMm = minMm,
            pastHourMaxMm = maxMm,
            observedAt = root.optString("updateTime"),
        )
    }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name).takeIf { it.isFinite() }
    }

    private companion object {
        const val HKO_RHRREAD_URL =
            "https://data.weather.gov.hk/weatherAPI/opendata/weather.php?dataType=rhrread&lang=tc"
    }
}
