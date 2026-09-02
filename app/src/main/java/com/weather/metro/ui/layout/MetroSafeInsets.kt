package com.weather.metro.ui.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Global top-chrome policy for interactive fullscreen tool headers.
 *
 * Weather Metro draws edge-to-edge and hides ordinary status-bar chrome, so using
 * safeDrawing here reserves substantially more vertical space than the physical
 * camera/notch needs. Reserve only the actual display cutout at the top; maps and
 * headers can then sit close to the hardware edge without being covered by a hole.
 */
@Composable
fun Modifier.metroSafeTop(): Modifier =
    windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))

/**
 * Compact top policy for the large, non-interactive Current / Forecast / Settings pivot.
 *
 * The primary pivot is visual identity rather than a row of touch targets, so reserving
 * the complete cutout height makes it sit visibly lower than fullscreen tool headers on
 * real punch-hole devices. Keep a small physical-camera allowance, capped at 18dp,
 * while letting the large typography occupy more of the edge-to-edge top band.
 */
@Composable
fun Modifier.metroPrimarySafeTop(): Modifier {
    val density = LocalDensity.current
    val cutoutTop = with(density) {
        WindowInsets.displayCutout.getTop(this).toDp()
    }
    return padding(top = cutoutTop.coerceAtMost(18.dp))
}

/** Global bottom-control policy for gesture/navigation safe areas. */
@Composable
fun Modifier.metroSafeBottom(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
