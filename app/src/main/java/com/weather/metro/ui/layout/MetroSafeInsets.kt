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
 * Keeps interactive top chrome clear of camera holes / display cutouts while
 * leaving the underlying map or backdrop edge-to-edge.
 */
@Composable
fun Modifier.metroSafeTop(): Modifier =
    windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))

/** Keeps bottom controls above gesture/navigation safe areas. */
@Composable
fun Modifier.metroSafeBottom(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
