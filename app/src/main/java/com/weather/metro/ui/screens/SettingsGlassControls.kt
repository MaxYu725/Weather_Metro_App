package com.weather.metro.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
                        if (checked) accent.copy(alpha = 0.94f) else Color.White.copy(alpha = 0.70f),
                        CircleShape,
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.66f), CircleShape),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .width(12.dp)
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.34f), CircleShape),
                )
            }
        }
    }
}

@Composable
internal fun SettingsGlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    accent: Color,
) {
    val fraction = ((value - 0.9f) / (1.5f - 0.9f)).coerceIn(0f, 1f)
    MetroGlassContextSurface(
        accent = accent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(horizontal = 12.dp),
        ) {
            val thumbSize = 28.dp
            val thumbTravel = (maxWidth - thumbSize).coerceAtLeast(0.dp)
            val trackShape = RoundedCornerShape(99.dp)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .align(Alignment.Center)
                    .clip(trackShape)
                    .background(Color.White.copy(alpha = 0.13f)),
            )
            Box(
                modifier = Modifier
                    .width(maxWidth * fraction)
                    .height(7.dp)
                    .align(Alignment.CenterStart)
                    .clip(trackShape)
                    .background(accent.copy(alpha = 0.58f)),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(7) {
                    Box(
                        Modifier
                            .size(4.dp)
                            .background(Color.White.copy(alpha = 0.42f), CircleShape),
                    )
                }
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = 0.9f..1.5f,
                steps = 5,
                modifier = Modifier.fillMaxSize(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.Transparent,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
            MetroGlassContextSurface(
                accent = accent,
                modifier = Modifier
                    .offset(x = thumbTravel * fraction)
                    .size(thumbSize)
                    .align(Alignment.CenterStart),
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(Color.White.copy(alpha = 0.92f), CircleShape),
                )
            }
        }
    }
}

@Composable
internal fun SettingsActionBadge(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    minWidth: Dp = 132.dp,
    onClick: (() -> Unit)? = null,
) {
    val actionModifier = modifier
        .widthIn(min = minWidth)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    MetroGlassContextSurface(
        accent = accent,
        modifier = actionModifier,
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.84f),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}
