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
        return endpointCandidates().firstOrNull()
    }

    /**
     * A Web App redeployment can invalidate a previously cached Apps Script URL.
     * Keep the newest FCM-supplied URL first, but retain the build-time production
     * URL as an independent recovery candidate until one of them is proven live.
     */
    fun endpointCandidates(): List<String> = endpointCandidates(
        stored = preferences.getString(KEY_ENDPOINT, null),
        configured = BuildConfig.NOTIFICATION_JOURNAL_URL,
    )

    fun isInitialized(): Boolean = preferences.getBoolean(KEY_INITIALIZED, false)

    fun cursor(): Long = preferences.getLong(KEY_CURSOR, 0L).coerceAtLeast(0L)

    fun initializeAt(value: Long) {
        require(value >= 0L) { "Journal cursor must be non-negative" }
        if (isInitialized()) return
        if (!preferences.edit()
                .putLong(KEY_CURSOR, value)
                .putBoolean(KEY_INITIALIZED, true)
                .commit()
        ) {
            throw IllegalStateException("Failed to initialize notification journal cursor")
        }
    }

    /**
     * If the first thing a fresh installation sees is an FCM wake-up, start one
     * cursor before that event. This prevents the bootstrap baseline from
     * classifying the wake-up event itself as historical.
     */
    fun initializeForWakeup(eventCursor: Long) {
        require(eventCursor > 0L) { "Wake-up cursor must be positive" }
        initializeAt((eventCursor - 1L).coerceAtLeast(0L))
    }

    fun advanceCursor(value: Long) {
        require(value >= 0L) { "Journal cursor must be non-negative" }
        if (!isInitialized()) {
            initializeAt(value)
            return
        }
        val current = cursor()
        if (value <= current) return
        if (!preferences.edit().putLong(KEY_CURSOR, value).commit()) {
            throw IllegalStateException("Failed to persist notification journal cursor")
        }
    }

    /**
     * Disabling notifications ends the current subscription window. The next
     * enable starts from a fresh server baseline rather than replaying alerts
     * that were intentionally ignored while notifications were off.
     */
    fun resetSubscriptionBaseline() {
        if (!preferences.edit()
                .remove(KEY_CURSOR)
                .putBoolean(KEY_INITIALIZED, false)
                .commit()
        ) {
            throw IllegalStateException("Failed to reset notification journal baseline")
        }
    }

    internal companion object {
        const val PREFERENCES_NAME = "weather_notification_journal"
        const val KEY_ENDPOINT = "endpoint_v1"
        const val KEY_CURSOR = "cursor_v1"
        const val KEY_INITIALIZED = "initialized_v1"

        internal fun endpointCandidates(stored: String?, configured: String?): List<String> =
            listOfNotNull(
                normaliseEndpoint(stored),
                normaliseEndpoint(configured),
            ).distinct()

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
