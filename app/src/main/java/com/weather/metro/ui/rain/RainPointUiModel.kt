package com.weather.metro.ui.rain

import com.weather.metro.domain.rain.RainPointForecast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class RainPointPeriodUi(
    val time: String,
    val amount: String,
    val nearby: String,
)

internal data class RainPointUiModel(
    val headline: String,
    val total: String,
    val peak: String,
    val rainStart: String,
    val periods: List<RainPointPeriodUi>,
    val quality: String?,
)

internal fun buildRainPointUiModel(forecast: RainPointForecast): RainPointUiModel {
    val summary = forecast.summary
    val qualityParts = listOfNotNull(
        forecast.quality?.freshness?.label ?: forecast.quality?.freshness?.status,
        forecast.quality?.spatial?.label ?: forecast.quality?.spatial?.status,
    ).filter { it.isNotBlank() }.distinct()

    return RainPointUiModel(
        headline = summary?.text?.takeIf { it.isNotBlank() } ?: "未來兩小時定點降雨",
        total = formatMm(summary?.totalMm),
        peak = formatMm(summary?.peakMm),
        rainStart = when {
            summary?.rainStartTime != null -> formatHktTime(summary.rainStartTime)
            summary?.rainStartLeadMinutes != null -> "+${summary.rainStartLeadMinutes} 分鐘"
            else -> "未見明顯降雨"
        },
        periods = forecast.periods.map { period ->
            RainPointPeriodUi(
                time = formatHktTime(period.time),
                amount = formatMm(period.amountMm),
                nearby = "附近最高 ${formatMm(period.nearbyMaxMm)}",
            )
        },
        quality = qualityParts.takeIf { it.isNotEmpty() }?.joinToString(" · "),
    )
}

internal fun formatMm(value: Double?): String = when {
    value == null || !value.isFinite() -> "-- mm"
    value == 0.0 -> "0 mm"
    value >= 10.0 -> String.format(Locale.US, "%.1f mm", value)
    else -> String.format(Locale.US, "%.2f mm", value).trimTrailingZeroes()
}

internal fun formatHktTime(value: String): String = runCatching {
    HKT_TIME.format(Instant.parse(value))
}.getOrElse {
    value.replace("T", " ").take(16)
}

private fun String.trimTrailingZeroes(): String =
    replace(Regex("(\\.\\d*?[1-9])0+ mm$"), "$1 mm")
        .replace(Regex("\\.0+ mm$"), " mm")

private val HKT_TIME: DateTimeFormatter = DateTimeFormatter
    .ofPattern("HH:mm")
    .withZone(ZoneId.of("Asia/Hong_Kong"))
