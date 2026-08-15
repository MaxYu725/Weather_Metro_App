package com.weather.metro.ui.rain

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weather.metro.ui.components.MetroStat
import com.weather.metro.ui.components.MetroTile
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
        Text(
            text = state.location?.label ?: "目前位置",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Light,
        )
        Text(
            text = "香港天文台格點降雨預報 · 每格為 30 分鐘累積雨量",
            color = LocalMetroSubText.current,
            fontSize = 11.sp,
        )

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
                RainResourceStatus.LOADING -> LoadingPointState(
                    pageColour = pageColour,
                    hasRetainedData = state.pointForecast.value != null,
                )
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
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = if (state.pointForecast.isStale) "離線／舊資料" else "最新可用資料",
                                        color = if (state.pointForecast.isStale) Color(0xFFFFC107) else Color.White.copy(alpha = 0.72f),
                                        fontSize = 10.sp,
                                    )
                                }
                                Text(
                                    text = "refresh",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clickable(onClick = onRefresh)
                                        .padding(start = 12.dp, top = 4.dp, bottom = 8.dp),
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
                            Text("30 分鐘時段", color = Color.White.copy(alpha = 0.68f), fontSize = 10.sp)
                            Spacer(Modifier.height(7.dp))
                            model.periods.forEachIndexed { index, period ->
                                if (index > 0) {
                                    HorizontalDivider(color = Color.White.copy(alpha = 0.20f))
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        period.time,
                                        color = Color.White.copy(alpha = 0.78f),
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(0.75f),
                                    )
                                    Text(
                                        period.amount,
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Light,
                                        modifier = Modifier.weight(0.85f),
                                    )
                                    Text(
                                        period.nearby,
                                        color = Color.White.copy(alpha = 0.68f),
                                        fontSize = 10.sp,
                                        modifier = Modifier.weight(1.4f),
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
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            RainHostViewModel.SUPPORTED_POINT_RADII_KM.sorted().forEach { radiusKm ->
                val selected = selectedRadiusKm == radiusKm
                val reduceMotion = LocalReduceMotion.current
                val interactionSource = remember { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                val background by animateColorAsState(
                    targetValue = if (selected) pageColour else Color(0xFF202020),
                    animationSpec = tween(if (reduceMotion) 100 else 180),
                    label = "rain radius background",
                )
                val contentColour by animateColorAsState(
                    targetValue = if (selected) Color.White else LocalMetroSubText.current,
                    animationSpec = tween(if (reduceMotion) 100 else 180),
                    label = "rain radius text",
                )
                val scale by animateFloatAsState(
                    targetValue = if (pressed && !reduceMotion) 0.94f else 1f,
                    animationSpec = tween(if (reduceMotion) 100 else 110),
                    label = "rain radius press",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .background(background)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = LocalIndication.current,
                        ) { onRadiusChange(radiusKm) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$radiusKm km",
                        color = contentColour,
                        fontSize = 12.sp,
                    )
                }
            }
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
            Text("load", color = pageColour, fontSize = 13.sp)
        }
    }
}

@Composable
private fun LoadingPointState(pageColour: Color, hasRetainedData: Boolean) {
    ToolLoadingPanel(
        title = if (hasRetainedData) "正在更新定點降雨" else "正在載入定點降雨",
        detail = if (hasRetainedData) "保留最近資料，正在取得最新格點" else "正在配對位置與香港天文台格點",
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
            Text("retry", color = pageColour, fontSize = 13.sp)
        }
    }
}
