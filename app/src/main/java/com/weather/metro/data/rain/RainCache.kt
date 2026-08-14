package com.weather.metro.data.rain

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

class RainCache(context: Context) {
    private val capabilitiesFile = AtomicFile(File(context.filesDir, "rain_capabilities_v1.json"))
    private val pointFile = AtomicFile(File(context.filesDir, "rain_point_v1.json"))

    suspend fun writeCapabilities(payload: String) = writeAtomic(capabilitiesFile, payload)

    suspend fun readCapabilities(): String? = readAtomic(capabilitiesFile)

    suspend fun writePoint(
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
        payload: String,
    ) {
        val record = JSONObject()
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("radiusKm", radiusKm)
            .put("payload", payload)
            .put("cachedAt", System.currentTimeMillis())
        writeAtomic(pointFile, record.toString())
    }

    suspend fun readPoint(
        latitude: Double,
        longitude: Double,
        radiusKm: Int,
    ): String? {
        val record = readAtomic(pointFile)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val cachedLat = record.optDouble("latitude", Double.NaN)
        val cachedLon = record.optDouble("longitude", Double.NaN)
        val cachedRadius = record.optInt("radiusKm", -1)
        if (!cachedLat.isFinite() || !cachedLon.isFinite()) return null
        if (abs(cachedLat - latitude) > LOCATION_EPSILON) return null
        if (abs(cachedLon - longitude) > LOCATION_EPSILON) return null
        if (cachedRadius != radiusKm) return null
        return record.optString("payload").takeIf { it.isNotBlank() }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            capabilitiesFile.delete()
            pointFile.delete()
        }
    }

    private suspend fun writeAtomic(file: AtomicFile, payload: String) = withContext(Dispatchers.IO) {
        val stream = file.startWrite()
        try {
            stream.write(payload.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    private suspend fun readAtomic(file: AtomicFile): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.baseFile.exists()) null
            else file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

    companion object {
        private const val LOCATION_EPSILON = 0.000001
    }
}
