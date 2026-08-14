package com.weather.metro.ui.rain

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weather.metro.ui.components.MetroProgress
import com.weather.metro.ui.components.MetroStat
import com.weather.metro.ui.components.MetroTile
import com.weather.metro.ui.theme.LocalMetroSubText

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

        when (state.pointForecast.status) {
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

        state.pointForecast.value?.let { forecast ->
            val model = buildRainPointUiModel(forecast)
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
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .background(if (selected) pageColour else Color(0xFF202020))
                        .clickable { onRadiusChange(radiusKm) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$radiusKm km",
                        color = if (selected) Color.White else LocalMetroSubText.current,
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
        background = Color(0xFF181818),
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
    Column {
        MetroProgress(colour = pageColour)
        Text(
            text = if (hasRetainedData) "正在更新定點降雨…" else "正在載入定點降雨…",
            color = LocalMetroSubText.current,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ErrorPointState(pageColour: Color, message: String, onRefresh: () -> Unit) {
    MetroTile(
        seed = "rain-point-error",
        background = Color(0xFF202020),
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
