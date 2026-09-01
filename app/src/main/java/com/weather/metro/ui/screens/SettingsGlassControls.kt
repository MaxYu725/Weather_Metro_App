package com.weather.metro.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weather.metro.ui.components.MetroGlassContextSurface

@Composable
internal fun SettingsPageChip(
    label: String,
    accent: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    MetroGlassContextSurface(
        accent = accent,
        modifier = modifier
            .height(44.dp)
            .clickable(onClick = onClick),
    ) {
        Text(
            text = if (selected) "✓ $label" else label,
            color = Color.White.copy(alpha = if (selected) 1f else 0.78f),
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
internal fun SettingsAccentSwatch(
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    MetroGlassContextSurface(
        accent = accent,
        modifier = Modifier
            .size(42.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(accent, CircleShape)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, Color.White.copy(alpha = 0.92f), CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun SettingsGlassToggle(
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 24.dp else 0.dp,
        label = "settings glass toggle",
    )
    MetroGlassContextSurface(
        accent = if (checked) accent else Color.White.copy(alpha = 0.18f),
        modifier = Modifier
            .width(58.dp)
            .height(34.dp)
            .clickable { onCheckedChange(!checked) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .offset(x = knobOffset)
                    .size(26.dp)
                    .background(
                        if (checked) accent else Color.White.copy(alpha = 0.72f),
                        CircleShape,
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.66f), CircleShape),
            )
        }
    }
}

@Composable
internal fun SettingsGlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    accent: Color,
) {
    MetroGlassContextSurface(
        accent = accent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.9f..1.5f,
            steps = 5,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = accent.copy(alpha = 0.92f),
                inactiveTrackColor = Color.White.copy(alpha = 0.16f),
                activeTickColor = Color.White.copy(alpha = 0.72f),
                inactiveTickColor = Color.White.copy(alpha = 0.28f),
            ),
        )
    }
}

@Composable
internal fun SettingsActionBadge(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    MetroGlassContextSurface(
        accent = accent,
        modifier = modifier,
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.84f),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
