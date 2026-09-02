package com.weather.metro.ui.rain

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weather.metro.ui.components.MetroGlassContextSurface
import com.weather.metro.ui.components.MetroStat
import com.weather.metro.ui.components.MetroTile
import com.weather.metro.ui.motion.MetroPressPreset
import com.weather.metro.ui.motion.metroPressMotion
import com.weather.metro.ui.theme.LocalMetroSubText
import com.weather.metro.ui.theme.LocalReduceMotion
import com.weather.metro.ui.tools.ToolLoadingPanel

@Composable
fun RainPointPanel(
    state: RainHostState,
    pageColour: Color,
    selectedRadiusKm: Int,
    onRadiusChange: (Int) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        RadiusSelector(
            selectedRadiusKm = selectedRadiusKm,
            pageColour = pageColour,
            onRadiusChange = onRadiusChange,
        )

        val reduceMotion = LocalReduceMotion.current
        AnimatedContent(
            targetState = state.pointForecast.status,
            transitionSpec = {
                fadeIn(tween(if (reduceMotion) 120 else 220)) togetherWith
                    fadeOut(tween(if (reduceMotion) 100 else 150))
            },
            label = "point rainfall state",
        ) { status ->
            when (status) {
                RainResourceStatus.IDLE -> EmptyPointState(pageColour, onRefresh)
                RainResourceStatus.LOADING -> if (state.pointForecast.value == null) {
                    LoadingPointState(pageColour)
                }
                RainResourceStatus.ERROR -> ErrorPointState(
                    pageColour = pageColour,
                    message = state.pointForecast.errorMessage ?: "定點降雨暫時無法載入",
                    onRefresh = onRefresh,
                )
                RainResourceStatus.READY -> Unit
            }
        }

        val forecast = state.pointForecast.value
        AnimatedVisibility(
            visible = forecast != null,
            enter = if (reduceMotion) {
                fadeIn(tween(120))
            } else {
                fadeIn(tween(280)) + slideInVertically(tween(360)) { height -> height / 8 }
            },
            exit = if (reduceMotion) {
                fadeOut(tween(100))
            } else {
                fadeOut(tween(160)) + slideOutVertically(tween(180)) { height -> height / 12 }
            },
        ) {
            forecast?.let { availableForecast ->
                val model = buildRainPointUiModel(availableForecast)
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    MetroTile(
                        seed = "rain-point-summary",
                        background = pageColour,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = model.headline,
                                        color = Color.White,
                                        fontSize = 23.sp,
                                        lineHeight = 28.sp,
                                        fontWeight = FontWeight.Light,
                                    )
                                    Spacer(Modifier.height(5.dp))
                                    RainPointStatusBadge(
                                        stale = state.pointForecast.isStale,
                                        pageColour = pageColour,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                RainPointActionBadge(
                                    refreshing = state.pointForecast.status == RainResourceStatus.LOADING,
                                    pageColour = pageColour,
                                    onClick = onRefresh,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                MetroStat("兩小時總雨量", model.total, Modifier.weight(1f))
                                MetroStat("最高時段", model.peak, Modifier.weight(1f))
                                MetroStat("開始下雨", model.rainStart, Modifier.weight(1f))
                            }
                            model.quality?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(it, color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp)
                            }
                            state.pointForecast.errorMessage?.let {
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    text = it,
                                    color = Color(0xFFFFC107),
                                    fontSize = 10.sp,
                                )
                            }
                        }
                    }

                    MetroTile(
                        seed = "rain-point-periods",
                        background = pageColour,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("未來兩小時", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Light)
                            Text("每列為 30 分鐘累積雨量", color = Color.White.copy(alpha = 0.68f), fontSize = 10.sp)
                            Spacer(Modifier.height(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                model.periods.forEach { period ->
                                    RainPeriodGlassRow(
                                        time = period.time,
                                        amount = period.amount,
                                        nearby = period.nearby,
                                        pageColour = pageColour,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadiusSelector(
    selectedRadiusKm: Int,
    pageColour: Color,
    onRadiusChange: (Int) -> Unit,
) {
    Column {
        Text("附近範圍", color = LocalMetroSubText.current, fontSize = 11.sp)
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RainHostViewModel.SUPPORTED_POINT_RADII_KM.sorted().forEach { radiusKm ->
                val selected = selectedRadiusKm == radiusKm
                val interactionSource = remember { MutableInteractionSource() }
                MetroGlassContextSurface(
                    accent = if (selected) pageColour else Color.White.copy(alpha = 0.10f),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .metroPressMotion(
                            interactionSource = interactionSource,
                            preset = MetroPressPreset.CompactControl,
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = LocalIndication.current,
                        ) { onRadiusChange(radiusKm) },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                color = if (selected) pageColour.copy(alpha = 0.30f) else Color.Transparent,
                                shape = RoundedCornerShape(15.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (selected) "✓ $radiusKm km" else "$radiusKm km",
                            color = if (selected) Color.White else LocalMetroSubText.current,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RainPointActionBadge(
    refreshing: Boolean,
    pageColour: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val reduceMotion = LocalReduceMotion.current
    MetroGlassContextSurface(
        accent = pageColour,
        modifier = Modifier
            .width(56.dp)
            .height(32.dp)
            .metroPressMotion(
                interactionSource = interactionSource,
                preset = MetroPressPreset.CompactControl,
                enabled = !refreshing,
            )
            .clickable(
                enabled = !refreshing,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
    ) {
        AnimatedContent(
            targetState = refreshing,
            transitionSpec = {
                if (reduceMotion) {
                    fadeIn(tween(100)) togetherWith fadeOut(tween(80))
                } else {
                    (fadeIn(tween(150)) + slideInVertically(tween(170)) { height -> height / 3 }) togetherWith
                        (fadeOut(tween(110)) + slideOutVertically(tween(140)) { height -> -height / 3 })
                }
            },
            contentKey = { it },
            label = "point refresh state",
        ) { isRefreshing ->
            Text(
                text = if (isRefreshing) "更新中" else "更新",
                color = if (isRefreshing) LocalMetroSubText.current else Color.White.copy(alpha = 0.90f),
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RainPointStatusBadge(
    stale: Boolean,
    pageColour: Color,
) {
    MetroGlassContextSurface(
        accent = if (stale) Color(0xFFFFC107) else pageColour,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (stale) "離線／舊資料" else "最新可用資料",
                color = if (stale) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.78f),
                fontSize = 9.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RainPeriodGlassRow(
    time: String,
    amount: String,
    nearby: String,
    pageColour: Color,
) {
    MetroGlassContextSurface(
        accent = pageColour.copy(alpha = 0.65f),
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = time,
                color = Color.White.copy(alpha = 0.74f),
                fontSize = 12.sp,
                modifier = Modifier.weight(0.75f),
            )
            Text(
                text = amount,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.weight(0.85f),
            )
            Text(
                text = nearby,
                color = Color.White.copy(alpha = 0.66f),
                fontSize = 10.sp,
                modifier = Modifier.weight(1.4f),
            )
        }
    }
}

@Composable
private fun EmptyPointState(pageColour: Color, onRefresh: () -> Unit) {
    MetroTile(
        seed = "rain-point-empty",
        background = pageColour,
        modifier = Modifier.fillMaxWidth(),
        onClick = onRefresh,
    ) {
        Column {
            Text("載入定點降雨", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Light)
            Text("使用 Weather App 目前位置", color = LocalMetroSubText.current, fontSize = 11.sp)
            Spacer(Modifier.height(7.dp))
            Text("點按載入 ›", color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun LoadingPointState(pageColour: Color) {
    ToolLoadingPanel(
        title = "正在載入定點降雨",
        detail = "正在配對位置與香港天文台格點",
        accent = pageColour,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorPointState(pageColour: Color, message: String, onRefresh: () -> Unit) {
    MetroTile(
        seed = "rain-point-error",
        background = pageColour,
        modifier = Modifier.fillMaxWidth(),
        onClick = onRefresh,
    ) {
        Column {
            Text("定點降雨暫時無法使用", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(4.dp))
            Text(message, color = LocalMetroSubText.current, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Text("點按重試 ›", color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp)
        }
    }
}
