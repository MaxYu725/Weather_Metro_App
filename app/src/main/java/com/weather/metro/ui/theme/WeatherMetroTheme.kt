package com.weather.metro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.weather.metro.data.settings.UiSettings

val LocalMetroAccent = staticCompositionLocalOf { Color(0xFF1BA1E2) }
val LocalMetroSubText = staticCompositionLocalOf { Color(0xFFAAAAAA) }
val LocalMetroSurface = staticCompositionLocalOf { Color(0xD911161A) }
val LocalMetroOutline = staticCompositionLocalOf { Color.White.copy(alpha = 0.12f) }
val LocalPatternIntensity = staticCompositionLocalOf { 0.18f }
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun WeatherMetroTheme(settings: UiSettings, content: @Composable () -> Unit) {
    val accent = argbColor(settings.pageColours.currentArgb)
    val subText = if (settings.highContrast) Color.White else Color(0xFFAAAAAA)
    val metroSurface = if (settings.highContrast) Color(0xF20B0F12) else Color(0xD911161A)
    val metroOutline = Color.White.copy(alpha = if (settings.highContrast) 0.26f else 0.12f)
    val systemDensity = LocalDensity.current
    val scaledDensity = Density(
        density = systemDensity.density,
        fontScale = systemDensity.fontScale * settings.textScale,
    )
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = Color.White,
        background = Color.Black,
        onBackground = Color.White,
        surface = Color(0xFF161616),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF252525),
        onSurfaceVariant = subText,
        error = Color(0xFFE51400),
    )

    CompositionLocalProvider(
        LocalMetroAccent provides accent,
        LocalMetroSubText provides subText,
        LocalMetroSurface provides metroSurface,
        LocalMetroOutline provides metroOutline,
        LocalPatternIntensity provides settings.patternIntensity,
        LocalReduceMotion provides settings.reduceMotion,
        LocalDensity provides scaledDensity,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = metroTypography(),
            content = content,
        )
    }
}

@Composable
fun MetroPageTheme(accent: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMetroAccent provides accent) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(primary = accent, onPrimary = Color.White),
            typography = MaterialTheme.typography,
            content = content,
        )
    }
}

internal fun argbColor(argb: Long): Color = Color(argb)

private fun metroTypography() = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 58.sp,
        lineHeight = 58.sp,
        letterSpacing = (-1.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 46.sp,
        lineHeight = 48.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 30.sp,
        lineHeight = 34.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 24.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 18.sp,
        lineHeight = 29.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 0.4.sp,
    ),
)
