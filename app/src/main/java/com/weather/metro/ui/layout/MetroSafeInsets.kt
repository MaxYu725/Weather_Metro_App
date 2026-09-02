package com.weather.metro.ui.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Global Weather Metro policy for top interactive chrome: respect punch holes,
 * notches and other system-reserved top areas, while maps and decorative
 * backdrops remain edge-to-edge behind the reserved area.
 */
@Composable
fun Modifier.metroSafeTop(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))

/** Global policy for bottom controls above gesture/navigation safe areas. */
@Composable
fun Modifier.metroSafeBottom(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
