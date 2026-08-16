package com.weather.metro.notification

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class NotificationJournalPage(
    val nextCursor: Long,
    val latestCursor: Long,
    val hasMore: Boolean,
    val events: List<WeatherNotificationEvent>,
)

internal object NotificationJournalCodec {
    private const val SUPPORTED_SCHEMA_VERSION = 1

    fun decodePage(value: String): NotificationJournalPage {
        val root = JSONObject(value)
        require(root.getInt("schemaVersion") == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported notification journal schema"
        }
        val nextCursor = root.getLong("nextCursor")
        val latestCursor = root.getLong("latestCursor")
        require(nextCursor >= 0L && latestCursor >= 0L && nextCursor <= latestCursor) {
            "Invalid notification journal cursor range"
        }

        val array = root.getJSONArray("events")
        val events = ArrayList<WeatherNotificationEvent>(array.length())
        var previousCursor = 0L
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val event = decodeEvent(item)
            require(event.journalCursor > previousCursor) { "Notification journal events are not ordered" }
            previousCursor = event.journalCursor
            events += event
        }
        if (events.isNotEmpty()) {
            require(events.last().journalCursor == nextCursor) {
                "Notification journal next cursor does not match the returned events"
            }
        }

        return NotificationJournalPage(
            nextCursor = nextCursor,
            latestCursor = latestCursor,
            hasMore = root.getBoolean("hasMore"),
            events = events,
        )
    }

    private fun decodeEvent(item: JSONObject): WeatherNotificationEvent {
        val eventId = item.getString("eventId")
        val title = item.getString("title")
        val body = item.getString("body")
        val rawChannel = item.getString("channel")
        val channel = NotificationContract.channelOrDefault(rawChannel)
        val target = item.getString("target")
        val eventKind = item.getString("eventKind").uppercase()
        val sourceType = item.getString("sourceType").uppercase()
        val journalCursor = item.getLong("journalCursor")
        val sentAtEpochMillis = item.getLong("sentAtEpochMillis")

        require(eventId.isNotBlank() && eventId.length <= 200) { "Invalid journal event id" }
        require(title.isNotBlank()) { "Journal event title is empty" }
        require(body.isNotBlank()) { "Journal event body is empty" }
        require(rawChannel == channel) { "Unknown journal notification channel" }
        require(NotificationContract.isSafeTarget(target)) { "Unsafe journal notification target" }
        require(eventKind.isNotBlank() && eventKind.length <= 40) { "Invalid journal event kind" }
        require(sourceType.isNotBlank() && sourceType.length <= 40) { "Invalid journal source type" }
        require(journalCursor > 0L) { "Invalid journal event cursor" }
        require(sentAtEpochMillis > 0L) { "Invalid journal event timestamp" }

        return WeatherNotificationEvent(
            eventId = eventId,
            title = title,
            body = body,
            channel = channel,
            target = target,
            alertId = item.optString("alertId"),
            alertCode = item.optString("alertCode"),
            eventKind = eventKind,
            sourceType = sourceType,
            sourceTime = item.optString("sourceTime"),
            journalCursor = journalCursor,
            sentAtEpochMillis = sentAtEpochMillis,
        )
    }
}

internal class NotificationJournalClient {
    fun fetch(endpoint: String, after: Long, limit: Int = 100): NotificationJournalPage {
        require(after >= 0L) { "Journal cursor must be non-negative" }
        require(limit in 1..200) { "Journal page size must be between 1 and 200" }
        val separator = if (endpoint.contains('?')) '&' else '?'
        val url = URL("$endpoint${separator}after=$after&limit=$limit")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw IOException("Notification journal HTTP $code: ${error.take(500)}")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            NotificationJournalCodec.decodePage(body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 15_000
    }
}
