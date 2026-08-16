package com.weather.metro.notification

import android.content.Context
import org.json.JSONObject

internal data class LocationHeavyRainState(
    val district: String = "",
    val activeLevel: LocationHeavyRainLevel = LocationHeavyRainLevel.NONE,
    val episodeId: String = "",
    val pendingLevel: LocationHeavyRainLevel? = null,
    val pendingObservedMm: Double? = null,
    val pendingObservedAt: String = "",
    val lastObservedMm: Double? = null,
    val lastObservedAt: String = "",
    val lastCheckedEpochMs: Long = 0,
    val lastNotificationEpochMs: Long = 0,
    val status: String = "IDLE",
    val lastError: String = "",
)

/** Durable local episode/outbox state for location-specific heavy-rain alerts. */
internal class LocationHeavyRainStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun read(): LocationHeavyRainState {
        val raw = preferences.getString(KEY_STATE, null) ?: return LocationHeavyRainState()
        return runCatching {
            val json = JSONObject(raw)
            LocationHeavyRainState(
                district = json.optString("district"),
                activeLevel = level(json.optString("activeLevel")),
                episodeId = json.optString("episodeId"),
                pendingLevel = json.optString("pendingLevel").takeIf { it.isNotBlank() }?.let(::level),
                pendingObservedMm = json.optNullableDouble("pendingObservedMm"),
                pendingObservedAt = json.optString("pendingObservedAt"),
                lastObservedMm = json.optNullableDouble("lastObservedMm"),
                lastObservedAt = json.optString("lastObservedAt"),
                lastCheckedEpochMs = json.optLong("lastCheckedEpochMs"),
                lastNotificationEpochMs = json.optLong("lastNotificationEpochMs"),
                status = json.optString("status", "IDLE"),
                lastError = json.optString("lastError"),
            )
        }.getOrElse {
            LocationHeavyRainState(status = "STATE_RESET", lastError = "Invalid local heavy-rain state was ignored")
        }
    }

    fun write(state: LocationHeavyRainState) {
        val json = JSONObject()
            .put("district", state.district)
            .put("activeLevel", state.activeLevel.name)
            .put("episodeId", state.episodeId)
            .put("pendingLevel", state.pendingLevel?.name ?: "")
            .put("pendingObservedMm", state.pendingObservedMm ?: JSONObject.NULL)
            .put("pendingObservedAt", state.pendingObservedAt)
            .put("lastObservedMm", state.lastObservedMm ?: JSONObject.NULL)
            .put("lastObservedAt", state.lastObservedAt)
            .put("lastCheckedEpochMs", state.lastCheckedEpochMs)
            .put("lastNotificationEpochMs", state.lastNotificationEpochMs)
            .put("status", state.status)
            .put("lastError", state.lastError.take(500))
            .toString()
        check(preferences.edit().putString(KEY_STATE, json).commit()) {
            "Failed to persist location heavy-rain state"
        }
    }

    fun reset() {
        check(preferences.edit().remove(KEY_STATE).commit()) {
            "Failed to reset location heavy-rain state"
        }
    }

    private fun level(name: String): LocationHeavyRainLevel =
        LocationHeavyRainLevel.entries.firstOrNull { it.name == name } ?: LocationHeavyRainLevel.NONE

    private fun JSONObject.optNullableDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name).takeIf { it.isFinite() }
    }

    private companion object {
        const val PREFERENCES_NAME = "weather_metro_location_heavy_rain"
        const val KEY_STATE = "state_v1"
    }
}
