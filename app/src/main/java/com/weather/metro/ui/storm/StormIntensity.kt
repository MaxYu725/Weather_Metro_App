package com.weather.metro.ui.storm

import com.weather.metro.domain.storm.StormPoint

internal enum class StormIntensityClass(
    val wireCode: String,
    val labelZh: String,
    val colorHex: String,
) {
    SUPER_TYPHOON("superty", "超強颱風", "#E51400"),
    SEVERE_TYPHOON("sty", "強颱風", "#E671B8"),
    TYPHOON("ty", "颱風", "#FA6800"),
    SEVERE_TROPICAL_STORM("sts", "強烈熱帶風暴", "#F09609"),
    TROPICAL_STORM("ts", "熱帶風暴", "#1BA1E2"),
    TROPICAL_DEPRESSION("td", "熱帶低氣壓", "#339933"),
}

internal fun classifyStormIntensity(point: StormPoint): StormIntensityClass? {
    val code = point.intensityCode.orEmpty().trim().lowercase()
    StormIntensityClass.entries.firstOrNull { it.wireCode == code }?.let { return it }

    val raw = listOfNotNull(point.intensityCode, point.intensityLabel)
        .joinToString(" ")
        .trim()
        .lowercase()
    val compact = raw.replace(Regex("[^a-z0-9\\u3400-\\u9fff]+"), "")

    when {
        compact.contains("supertyphoon") || compact.contains("超強颱風") || compact.contains("超强台风") ->
            return StormIntensityClass.SUPER_TYPHOON
        compact.contains("severetyphoon") || compact.contains("強颱風") || compact.contains("强台风") ->
            return StormIntensityClass.SEVERE_TYPHOON
        compact.contains("severetropicalstorm") || compact.contains("強烈熱帶風暴") || compact.contains("强热带风暴") ->
            return StormIntensityClass.SEVERE_TROPICAL_STORM
        compact.contains("tropicaldepression") || compact.contains("熱帶低氣壓") || compact.contains("热带低压") || compact.contains("熱帯低気圧") ->
            return StormIntensityClass.TROPICAL_DEPRESSION
        compact.contains("tropicalstorm") || compact.contains("熱帶風暴") || compact.contains("热带风暴") ->
            return StormIntensityClass.TROPICAL_STORM
        compact == "ty" || compact.contains("typhoon") || compact.contains("颱風") || compact.contains("台风") ->
            return StormIntensityClass.TYPHOON
    }

    val wind = point.windSpeedMs ?: return null
    return when {
        wind >= 51.0 -> StormIntensityClass.SUPER_TYPHOON
        wind >= 41.0 -> StormIntensityClass.SEVERE_TYPHOON
        wind >= 33.0 -> StormIntensityClass.TYPHOON
        wind >= 25.0 -> StormIntensityClass.SEVERE_TROPICAL_STORM
        wind >= 17.0 -> StormIntensityClass.TROPICAL_STORM
        else -> StormIntensityClass.TROPICAL_DEPRESSION
    }
}

internal fun stormIntensityColorHex(point: StormPoint): String =
    classifyStormIntensity(point)?.colorHex ?: "#8A8A8A"

internal fun stormIntensityDisplayLabel(point: StormPoint): String =
    classifyStormIntensity(point)?.labelZh
        ?: point.intensityLabel?.takeIf { it.isNotBlank() }
        ?: point.intensityCode?.takeIf { it.isNotBlank() }
        ?: "未提供"
