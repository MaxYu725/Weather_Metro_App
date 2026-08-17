package com.weather.metro.ui.screens

import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
internal fun personalizedSliderColors() = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = Color.White,
    inactiveTrackColor = Color.Black.copy(alpha = 0.55f),
    activeTickColor = Color.Black.copy(alpha = 0.4f),
    inactiveTickColor = Color.White.copy(alpha = 0.5f),
)
