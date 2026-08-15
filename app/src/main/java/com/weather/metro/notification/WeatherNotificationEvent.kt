package com.weather.metro.notification

import java.net.URI

object NotificationContract {
    const val URGENT = "weather_alert_urgent"
    const val GENERAL = "weather_alert_general"
    const val TIPS = "weather_tips"
    const val STATUS = "weather_service_status"
}

data class WeatherNotificationEvent(
    val eventId: String,
    val title: String,
    val body: String,
    val channel: String,
    val target: String,
    val alertId: String = "",
    val alertCode: String = "",
    val eventKind: String = "",
    val sentAtEpochMillis: Long,
)

object WeatherNotificationEventParser {
    private const val FALLBACK_TARGET = "weathermetro://current"
    private val allowedChannels = setOf(
        NotificationContract.URGENT,
        NotificationContract.GENERAL,
        NotificationContract.TIPS,
        NotificationContract.STATUS,
    )
    private val allowedTargets = setOf("current", "forecast", "tools", "settings")

    fun parse(
        data: Map<String, String>,
        messageId: String?,
        notificationTitle: String?,
        notificationBody: String?,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): WeatherNotificationEvent? {
        val body = (notificationBody ?: data["body"])
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val eventId = data["eventId"]?.trim()?.takeIf(String::isNotEmpty)
            ?: messageId?.trim()?.takeIf(String::isNotEmpty)
            ?: "local:$nowEpochMillis"
        val target = data["target"]?.takeIf(::isSafeTarget) ?: FALLBACK_TARGET
        return WeatherNotificationEvent(
            eventId = eventId.take(200),
            title = (notificationTitle ?: data["title"] ?: "香港天文台").trim().take(300),
            body = body.take(8_000),
            channel = data["channel"]?.takeIf(allowedChannels::contains)
                ?: NotificationContract.GENERAL,
            target = target,
            alertId = data["alertId"].orEmpty().take(200),
            alertCode = data["alertCode"].orEmpty().take(80),
            eventKind = data["eventKind"].orEmpty().uppercase().take(20),
            sentAtEpochMillis = data["sentAtEpochMs"]?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: nowEpochMillis,
        )
    }

    private fun isSafeTarget(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "weathermetro" && uri.host in allowedTargets
    }.getOrDefault(false)
}
