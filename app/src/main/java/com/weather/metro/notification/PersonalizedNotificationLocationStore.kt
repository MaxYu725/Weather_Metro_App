package com.weather.metro.notification

import android.content.Context
import com.weather.metro.domain.LocationInfo
import org.json.JSONObject

internal data class PersonalizedNotificationLocation(
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val district: String,
    val accuracyMetres: Int,
    val updatedAtEpochMs: Long,
)

/**
 * Stores only the last precise location already resolved by Weather Metro.
 *
 * The personalized notification path deliberately does not create a second
 * location owner and does not upload this value. Background workers consume the
 * host app's last precise fix instead of requesting background location access.
 */
internal class PersonalizedNotificationLocationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun record(location: LocationInfo, nowEpochMs: Long = System.currentTimeMillis()) {
        val accuracy = location.accuracyMetres ?: return
        val payload = JSONObject()
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("label", location.label)
            .put("district", location.district)
            .put("accuracyMetres", accuracy)
            .put("updatedAtEpochMs", nowEpochMs)
            .toString()
        check(preferences.edit().putString(KEY_LOCATION, payload).commit()) {
            "Failed to persist personalized notification location"
        }
    }

    fun read(): PersonalizedNotificationLocation? {
        val raw = preferences.getString(KEY_LOCATION, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            PersonalizedNotificationLocation(
                latitude = json.getDouble("latitude"),
                longitude = json.getDouble("longitude"),
                label = json.getString("label"),
                district = json.getString("district"),
                accuracyMetres = json.getInt("accuracyMetres"),
                updatedAtEpochMs = json.getLong("updatedAtEpochMs"),
            ).takeIf {
                it.latitude in -90.0..90.0 &&
                    it.longitude in -180.0..180.0 &&
                    it.district.isNotBlank() &&
                    it.accuracyMetres >= 0 &&
                    it.updatedAtEpochMs > 0
            }
        }.getOrNull()
    }

    fun clear() {
        check(preferences.edit().remove(KEY_LOCATION).commit()) {
            "Failed to clear personalized notification location"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "weather_metro_personalized_notification_location"
        const val KEY_LOCATION = "last_precise_location_v1"
    }
}
