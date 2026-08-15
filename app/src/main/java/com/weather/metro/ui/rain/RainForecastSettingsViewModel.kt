package com.weather.metro.ui.rain

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

enum class RainForecastPlaybackSpeed(
    val delayMs: Long,
    val label: String,
) {
    SLOW(1_100L, "慢"),
    NORMAL(750L, "標準"),
    FAST(500L, "快");

    companion object {
        fun fromDelay(value: Long): RainForecastPlaybackSpeed =
            entries.firstOrNull { it.delayMs == value } ?: NORMAL
    }
}

data class RainForecastDisplaySettings(
    val opacity: Float = DEFAULT_FORECAST_OPACITY,
    val playbackSpeed: RainForecastPlaybackSpeed = RainForecastPlaybackSpeed.NORMAL,
)

class RainForecastSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        RainForecastDisplaySettings(
            opacity = normalizedForecastOpacity(
                preferences.getFloat(KEY_OPACITY, DEFAULT_FORECAST_OPACITY),
            ),
            playbackSpeed = RainForecastPlaybackSpeed.fromDelay(
                preferences.getLong(KEY_PLAYBACK_DELAY_MS, RainForecastPlaybackSpeed.NORMAL.delayMs),
            ),
        ),
    )
    val state: StateFlow<RainForecastDisplaySettings> = _state.asStateFlow()

    fun setOpacity(value: Float) {
        val opacity = normalizedForecastOpacity(value)
        if (abs(_state.value.opacity - opacity) < 0.001f) return
        preferences.edit().putFloat(KEY_OPACITY, opacity).apply()
        _state.update { it.copy(opacity = opacity) }
    }

    fun setPlaybackSpeed(speed: RainForecastPlaybackSpeed) {
        if (_state.value.playbackSpeed == speed) return
        preferences.edit().putLong(KEY_PLAYBACK_DELAY_MS, speed.delayMs).apply()
        _state.update { it.copy(playbackSpeed = speed) }
    }

    companion object {
        private const val PREFERENCES_NAME = "weather_metro_forecast"
        private const val KEY_OPACITY = "opacity"
        private const val KEY_PLAYBACK_DELAY_MS = "playback_delay_ms"
    }
}

internal const val DEFAULT_FORECAST_OPACITY = 0.82f
internal const val FORECAST_OPACITY_STEP = 0.10f

internal fun normalizedForecastOpacity(value: Float): Float = value.coerceIn(0f, 1f)

internal fun nextForecastPlaybackSpeed(speed: RainForecastPlaybackSpeed): RainForecastPlaybackSpeed =
    when (speed) {
        RainForecastPlaybackSpeed.SLOW -> RainForecastPlaybackSpeed.NORMAL
        RainForecastPlaybackSpeed.NORMAL -> RainForecastPlaybackSpeed.FAST
        RainForecastPlaybackSpeed.FAST -> RainForecastPlaybackSpeed.SLOW
    }
