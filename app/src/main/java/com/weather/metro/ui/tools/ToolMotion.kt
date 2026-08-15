package com.weather.metro.ui.tools

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weather.metro.ui.components.MetroProgress
import com.weather.metro.ui.theme.LocalMetroSubText
import com.weather.metro.ui.theme.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun ToolLoadingPanel(
    title: String,
    detail: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "tool loading glyph")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tool loading phase",
    )
    val visiblePhase = if (reduceMotion) 0.18f else phase

    Column(
        modifier = modifier.widthIn(max = 240.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(58.dp)) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = accent.copy(alpha = 0.16f),
                radius = size.minDimension * (0.27f + visiblePhase * 0.08f),
                center = centre,
            )
            drawCircle(
                color = accent.copy(alpha = 0.72f),
                radius = size.minDimension * 0.10f,
                center = centre,
            )
            repeat(4) { index ->
                val angle = (visiblePhase * 2.0 * PI) + (index * PI / 2.0)
                val radius = size.minDimension * 0.34f
                drawCircle(
                    color = accent.copy(alpha = 0.30f + index * 0.14f),
                    radius = size.minDimension * 0.045f,
                    center = Offset(
                        x = centre.x + cos(angle).toFloat() * radius,
                        y = centre.y + sin(angle).toFloat() * radius,
                    ),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = detail,
            color = LocalMetroSubText.current,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        MetroProgress(colour = accent)
    }
}

@Composable
internal fun ToolInitialLoadingOverlay(
    visible: Boolean,
    title: String,
    detail: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val duration = if (reduceMotion) 1 else 240
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(duration)) + scaleIn(tween(duration), initialScale = 0.985f),
        exit = fadeOut(tween(duration)) + scaleOut(tween(duration), targetScale = 1.015f),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.78f)),
            contentAlignment = Alignment.Center,
        ) {
            ToolLoadingPanel(
                title = title,
                detail = detail,
                accent = accent,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
