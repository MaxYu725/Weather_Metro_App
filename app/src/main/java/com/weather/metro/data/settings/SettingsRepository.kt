package com.weather.metro.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

enum class PageColourSlot(val label: String) {
    CURRENT("current"),
    FORECAST("forecast"),
    TOOLS("tools"),
    SETTINGS("settings"),
}

object DefaultPageColours {
    const val CURRENT: Long = 0xFF1BA1E2
    const val FORECAST: Long = 0xFFA200FF
    const val TOOLS: Long = 0xFFF09609
    const val SETTINGS: Long = 0xFFE671B8
}

data class PageColours(
    val currentArgb: Long = DefaultPageColours.CURRENT,
    val forecastArgb: Long = DefaultPageColours.FORECAST,
    val toolsArgb: Long = DefaultPageColours.TOOLS,
    val settingsArgb: Long = DefaultPageColours.SETTINGS,
) {
    fun colour(slot: PageColourSlot): Long = when (slot) {
        PageColourSlot.CURRENT -> currentArgb
        PageColourSlot.FORECAST -> forecastArgb
        PageColourSlot.TOOLS -> toolsArgb
        PageColourSlot.SETTINGS -> settingsArgb
    }

    fun withColour(slot: PageColourSlot, argb: Long): PageColours = when (slot) {
        PageColourSlot.CURRENT -> copy(currentArgb = argb)
        PageColourSlot.FORECAST -> copy(forecastArgb = argb)
        PageColourSlot.TOOLS -> copy(toolsArgb = argb)
        PageColourSlot.SETTINGS -> copy(settingsArgb = argb)
    }
}

data class UiSettings(
    val pageColours: PageColours = PageColours(),
    val textScale: Float = 1f,
    val reduceMotion: Boolean = false,
    val highContrast: Boolean = false,
    val preciseLocation: Boolean = true,
    val notificationsEnabled: Boolean = true,
)

class SettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<UiSettings> = _settings.asStateFlow()

    fun setPageColour(slot: PageColourSlot, argb: Long) {
        val updated = _settings.value.copy(pageColours = _settings.value.pageColours.withColour(slot, argb))
        update(updated) { putLong(colourPreferenceKey(slot), argb) }
    }

    fun setTextScale(value: Float) {
        val coerced = quantizeTextScale(value)
        update(_settings.value.copy(textScale = coerced)) { putFloat(KEY_TEXT_SCALE, coerced) }
    }

    fun setReduceMotion(value: Boolean) =
        update(_settings.value.copy(reduceMotion = value)) { putBoolean(KEY_REDUCE_MOTION, value) }

    fun setHighContrast(value: Boolean) =
        update(_settings.value.copy(highContrast = value)) { putBoolean(KEY_HIGH_CONTRAST, value) }

    fun setPreciseLocation(value: Boolean) =
        update(_settings.value.copy(preciseLocation = value)) { putBoolean(KEY_PRECISE_LOCATION, value) }

    fun setNotificationsEnabled(value: Boolean) =
        update(_settings.value.copy(notificationsEnabled = value)) { putBoolean(KEY_NOTIFICATIONS, value) }

    private fun read() = UiSettings(
        pageColours = PageColours(
            currentArgb = preferences.getLong(
                KEY_CURRENT_COLOUR,
                preferences.getLong(KEY_LEGACY_ACCENT, DefaultPageColours.CURRENT),
            ),
            forecastArgb = preferences.getLong(KEY_FORECAST_COLOUR, DefaultPageColours.FORECAST),
            toolsArgb = preferences.getLong(KEY_TOOLS_COLOUR, DefaultPageColours.TOOLS),
            settingsArgb = preferences.getLong(KEY_SETTINGS_COLOUR, DefaultPageColours.SETTINGS),
        ),
        textScale = quantizeTextScale(preferences.getFloat(KEY_TEXT_SCALE, 1f)),
        reduceMotion = preferences.getBoolean(KEY_REDUCE_MOTION, false),
        highContrast = preferences.getBoolean(KEY_HIGH_CONTRAST, false),
        preciseLocation = preferences.getBoolean(KEY_PRECISE_LOCATION, true),
        notificationsEnabled = preferences.getBoolean(KEY_NOTIFICATIONS, true),
    )

    private inline fun update(
        updated: UiSettings,
        persist: SharedPreferences.Editor.() -> Unit,
    ) {
        if (updated == _settings.value) return
        _settings.value = updated
        preferences.edit().apply(persist).apply()
    }

    private fun colourPreferenceKey(slot: PageColourSlot): String = when (slot) {
        PageColourSlot.CURRENT -> KEY_CURRENT_COLOUR
        PageColourSlot.FORECAST -> KEY_FORECAST_COLOUR
        PageColourSlot.TOOLS -> KEY_TOOLS_COLOUR
        PageColourSlot.SETTINGS -> KEY_SETTINGS_COLOUR
    }

    private fun quantizeTextScale(value: Float): Float =
        ((value.coerceIn(0.9f, 1.5f) * 10f).roundToInt() / 10f)

    companion object {
        private const val PREFERENCES_NAME = "weather_metro_settings"
        private const val KEY_LEGACY_ACCENT = "accent"
        private const val KEY_CURRENT_COLOUR = "page_colour_current"
        private const val KEY_FORECAST_COLOUR = "page_colour_forecast"
        private const val KEY_TOOLS_COLOUR = "page_colour_tools"
        private const val KEY_SETTINGS_COLOUR = "page_colour_settings"
        private const val KEY_TEXT_SCALE = "text_scale"
        private const val KEY_REDUCE_MOTION = "reduce_motion"
        private const val KEY_HIGH_CONTRAST = "high_contrast"
        private const val KEY_PRECISE_LOCATION = "precise_location"
        private const val KEY_NOTIFICATIONS = "notifications"

        fun notificationsEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_NOTIFICATIONS, true)
    }
}
