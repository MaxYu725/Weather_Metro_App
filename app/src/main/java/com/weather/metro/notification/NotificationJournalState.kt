package com.weather.metro.notification

import android.content.Context
import com.weather.metro.BuildConfig
import java.net.URI

class NotificationJournalState(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun rememberEndpoint(value: String?): Boolean {
        val endpoint = normaliseEndpoint(value) ?: return false
        if (preferences.getString(KEY_ENDPOINT, null) == endpoint) return true
        if (!preferences.edit().putString(KEY_ENDPOINT, endpoint).commit()) {
            throw IllegalStateException("Failed to persist notification journal endpoint")
        }
        return true
    }

    fun endpoint(): String? {
        val stored = normaliseEndpoint(preferences.getString(KEY_ENDPOINT, null))
        if (stored != null) return stored
        val configured = normaliseEndpoint(BuildConfig.NOTIFICATION_JOURNAL_URL) ?: return null
        rememberEndpoint(configured)
        return configured
    }

    fun cursor(): Long = preferences.getLong(KEY_CURSOR, 0L).coerceAtLeast(0L)

    fun advanceCursor(value: Long) {
        require(value >= 0L) { "Journal cursor must be non-negative" }
        val current = cursor()
        if (value <= current) return
        if (!preferences.edit().putLong(KEY_CURSOR, value).commit()) {
            throw IllegalStateException("Failed to persist notification journal cursor")
        }
    }

    internal companion object {
        const val PREFERENCES_NAME = "weather_notification_journal"
        const val KEY_ENDPOINT = "endpoint_v1"
        const val KEY_CURSOR = "cursor_v1"

        fun normaliseEndpoint(value: String?): String? {
            val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return runCatching {
                val uri = URI(trimmed)
                val host = uri.host?.lowercase() ?: return@runCatching null
                if (uri.scheme != "https") return@runCatching null
                if (host != "script.google.com") return@runCatching null
                if (!uri.path.orEmpty().startsWith("/macros/s/") || !uri.path.orEmpty().endsWith("/exec")) {
                    return@runCatching null
                }
                uri.toASCIIString()
            }.getOrNull()
        }
    }
}
