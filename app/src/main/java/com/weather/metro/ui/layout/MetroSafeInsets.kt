package com.weather.metro.ui.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Global top-chrome policy: avoid punch holes, notches and other system-reserved
 * areas without shrinking edge-to-edge maps or decorative backdrops.
 */
@Composable
fun Modifier.metroSafeTop(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))

/** Global bottom-control policy for gesture/navigation safe areas. */
@Composable
fun Modifier.metroSafeBottom(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
