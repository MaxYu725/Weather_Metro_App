package com.weather.metro.ui.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Global top-chrome policy.
 *
 * Weather Metro draws edge-to-edge and hides ordinary status-bar chrome, so using
 * safeDrawing here reserves substantially more vertical space than the physical
 * camera/notch needs. Reserve only the actual display cutout at the top; maps and
 * headers can then sit close to the hardware edge without being covered by a hole.
 */
@Composable
fun Modifier.metroSafeTop(): Modifier =
    windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))

/** Global bottom-control policy for gesture/navigation safe areas. */
@Composable
fun Modifier.metroSafeBottom(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
