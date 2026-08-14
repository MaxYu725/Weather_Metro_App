package com.weather.metro.ui.storm

import com.weather.metro.domain.storm.StormPoint
import com.weather.metro.domain.storm.StormPointType
import org.junit.Assert.assertEquals
import org.junit.Test

class StormIntensityTest {
    @Test
    fun originalStormTrackPaletteIsPreserved() {
        assertEquals("#E51400", stormIntensityColorHex(point(code = "SuperTY")))
        assertEquals("#E671B8", stormIntensityColorHex(point(code = "STY")))
        assertEquals("#FA6800", stormIntensityColorHex(point(code = "TY")))
        assertEquals("#F09609", stormIntensityColorHex(point(code = "STS")))
        assertEquals("#1BA1E2", stormIntensityColorHex(point(code = "TS")))
        assertEquals("#339933", stormIntensityColorHex(point(code = "TD")))
    }

    @Test
    fun labelsAcrossSourcesNormalizeToTheSamePalette() {
        assertEquals("#E51400", stormIntensityColorHex(point(label = "超强台风")))
        assertEquals("#E671B8", stormIntensityColorHex(point(label = "Severe Typhoon")))
        assertEquals("#F09609", stormIntensityColorHex(point(label = "強烈熱帶風暴")))
        assertEquals("#1BA1E2", stormIntensityColorHex(point(label = "Tropical Storm")))
        assertEquals("#339933", stormIntensityColorHex(point(label = "熱帯低気圧")))
    }

    @Test
    fun windFallbackUsesNativeStormThresholds() {
        assertEquals("#E51400", stormIntensityColorHex(point(wind = 51.0)))
        assertEquals("#E671B8", stormIntensityColorHex(point(wind = 41.0)))
        assertEquals("#FA6800", stormIntensityColorHex(point(wind = 33.0)))
        assertEquals("#F09609", stormIntensityColorHex(point(wind = 25.0)))
        assertEquals("#1BA1E2", stormIntensityColorHex(point(wind = 17.0)))
        assertEquals("#339933", stormIntensityColorHex(point(wind = 10.0)))
    }

    @Test
    fun displayLabelUsesTraditionalChineseClassName() {
        assertEquals("颱風", stormIntensityDisplayLabel(point(code = "TY", label = "Typhoon")))
        assertEquals("超強颱風", stormIntensityDisplayLabel(point(label = "Super Typhoon")))
    }

    private fun point(
        code: String? = null,
        label: String? = null,
        wind: Double? = null,
    ) = StormPoint(
        validAt = "2026-08-14T12:00:00Z",
        latitude = 20.0,
        longitude = 130.0,
        pointType = StormPointType.ANALYSIS,
        intensityLabel = label,
        intensityCode = code,
        windSpeedMs = wind,
        pressureHpa = null,
        forecastHour = null,
        probabilityRadiusKm = null,
    )
}
