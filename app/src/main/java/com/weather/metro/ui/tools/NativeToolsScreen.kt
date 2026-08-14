package com.weather.metro.ui.tools

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.weather.metro.ui.components.MetroSectionLabel
import com.weather.metro.ui.components.MetroTile
import com.weather.metro.ui.rain.RainHostState
import com.weather.metro.ui.rain.RainHostViewModel
import com.weather.metro.ui.rain.RainPointPanel
import com.weather.metro.ui.rain.RainResourceStatus
import com.weather.metro.ui.theme.LocalMetroSubText

private const val DESTINATION_HOME = "home"
private const val DESTINATION_POINT = "point"

@Composable
fun NativeToolsScreen(
    pageColour: Color,
    rainState: RainHostState,
    isActive: Boolean,
    onRefreshPoint: (Int) -> Unit,
    onCancelPointRefresh: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(DESTINATION_HOME) }
    var selectedRadiusKm by rememberSaveable {
        mutableIntStateOf(rainState.pointRequest?.radiusKm ?: RainHostViewModel.DEFAULT_POINT_RADIUS_KM)
    }

    BackHandler(enabled = destination != DESTINATION_HOME) {
        onCancelPointRefresh()
        destination = DESTINATION_HOME
    }

    LaunchedEffect(isActive, destination) {
        if (
            isActive &&
            destination == DESTINATION_POINT &&
            rainState.location != null &&
            rainState.pointForecast.status == RainResourceStatus.IDLE
        ) {
            onRefreshPoint(selectedRadiusKm)
        }
    }

    if (destination == DESTINATION_POINT) {
        PointToolScreen(
            pageColour = pageColour,
            rainState = rainState,
            selectedRadiusKm = selectedRadiusKm,
            onRadiusChange = { radiusKm ->
                selectedRadiusKm = radiusKm
                onRefreshPoint(radiusKm)
            },
            onRefresh = { onRefreshPoint(selectedRadiusKm) },
            onBack = {
                onCancelPointRefresh()
                destination = DESTINATION_HOME
            },
        )
    } else {
        ToolsHome(
            pageColour = pageColour,
            onOpenPoint = {
                destination = DESTINATION_POINT
                onRefreshPoint(selectedRadiusKm)
            },
        )
    }
}

@Composable
private fun ToolsHome(
    pageColour: Color,
    onOpenPoint: () -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Text(
                "Rain / Storm 功能會直接在 Weather App 內開啟，不需要先進入獨立 Rain-Track 介面。",
                color = LocalMetroSubText.current,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        item { MetroSectionLabel("rain") }
        item {
            ToolTile(
                seed = "native-point-rain",
                title = "定點降雨",
                description = "目前位置 · 未來兩小時 · 附近雨勢",
                status = "native",
                background = pageColour,
                onClick = onOpenPoint,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                ToolTile(
                    seed = "native-radar",
                    title = "雷達",
                    description = "觀測動畫",
                    status = "next",
                    background = pageColour,
                    modifier = Modifier.weight(1f).height(132.dp),
                )
                ToolTile(
                    seed = "native-forecast-map",
                    title = "2小時預報",
                    description = "6分鐘步進",
                    status = "data ready",
                    background = pageColour,
                    modifier = Modifier.weight(1f).height(132.dp),
                )
            }
        }

        item { MetroSectionLabel("storm") }
        item {
            ToolTile(
                seed = "native-storm",
                title = "熱帶氣旋",
                description = "Storm native module 將接入此入口",
                status = "planned",
                background = pageColour,
            )
        }

        item { MetroSectionLabel("official links") }
        item {
            OfficialLink(
                title = "香港天文台雷達圖像",
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, "https://www.hko.gov.hk/tc/wxinfo/radars/radar-range.htm".toUri()),
                    )
                },
            )
        }
        item {
            OfficialLink(
                title = "閃電位置資訊",
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, "https://maps.weather.gov.hk/llis/llis.htm".toUri()),
                    )
                },
            )
        }
    }
}

@Composable
private fun PointToolScreen(
    pageColour: Color,
    rainState: RainHostState,
    selectedRadiusKm: Int,
    onRadiusChange: (Int) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Text(
                "‹ tools",
                color = pageColour,
                fontSize = 16.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(start = 0.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
            )
        }
        item {
            RainPointPanel(
                state = rainState,
                pageColour = pageColour,
                selectedRadiusKm = selectedRadiusKm,
                onRadiusChange = onRadiusChange,
                onRefresh = onRefresh,
            )
        }
    }
}

@Composable
private fun ToolTile(
    seed: String,
    title: String,
    description: String,
    status: String,
    background: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(142.dp),
    onClick: (() -> Unit)? = null,
) {
    MetroTile(
        seed = seed,
        background = background,
        modifier = modifier,
        onClick = onClick,
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(status, color = Color.White.copy(alpha = 0.68f), fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Light)
            Text(description, color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun OfficialLink(title: String, onClick: () -> Unit) {
    Text(
        text = "$title  ↗",
        color = LocalMetroSubText.current,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}
