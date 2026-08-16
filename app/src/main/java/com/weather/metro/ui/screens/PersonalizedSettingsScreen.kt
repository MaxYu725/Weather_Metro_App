package com.weather.metro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weather.metro.BuildConfig
import com.weather.metro.data.settings.PageColourSlot
import com.weather.metro.data.settings.UiSettings
import com.weather.metro.notification.PersonalizedNotificationDiagnosticVerdict
import com.weather.metro.notification.PersonalizedNotificationDiagnostics
import com.weather.metro.ui.components.MetroTile
import com.weather.metro.ui.theme.LocalMetroSubText
import com.weather.metro.ui.theme.argbColor
import kotlin.math.roundToInt

/**
 * Settings overload for the personalized-notification checkpoint.
 *
 * Keeping this as an overload avoids a broad edit to the mature weather-screen
 * owner while the location notification controls are being validated. The
 * legacy signature remains available to existing callers/tests.
 */
@Composable
fun SettingsScreen(
    settings: UiSettings,
    notificationDiagnostics: PersonalizedNotificationDiagnostics,
    pageColour: Color,
    onPageColourChange: (PageColourSlot, Long) -> Unit,
    onTextScaleChange: (Float) -> Unit,
    onReduceMotionChange: (Boolean) -> Unit,
    onHighContrastChange: (Boolean) -> Unit,
    onPreciseLocationChange: (Boolean) -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onLocationHeavyRainNotificationsChange: (Boolean) -> Unit,
    onPersonalizedRainNotificationsChange: (Boolean) -> Unit,
    onRefreshNotificationDiagnostics: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onClearCache: () -> Unit,
) {
    val accents = listOf(0xFF1BA1E2, 0xFF00A300, 0xFFA200FF, 0xFFE671B8, 0xFFF09609, 0xFFE51400)
    var selectedPage by rememberSaveable { mutableStateOf(PageColourSlot.CURRENT) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 22.dp,
            end = 16.dp,
            bottom = 48.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            MetroTile("page-colours", pageColour, Modifier.fillMaxWidth()) {
                Column {
                    PersonalizedSettingTitle("page accents", "為每個 Pivot 頁面設定局部強調色")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PageColourSlot.entries.forEach { slot ->
                            val selected = selectedPage == slot
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .background(argbColor(settings.pageColours.colour(slot)))
                                    .clickable { selectedPage = slot }
                                    .padding(horizontal = 3.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (selected) "✓ ${slot.label}" else slot.label,
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${selectedPage.label} colour",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        accents.forEach { value ->
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .background(argbColor(value))
                                    .clickable { onPageColourChange(selectedPage, value) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (settings.pageColours.colour(selectedPage) == value) {
                                    Text("✓", color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            MetroTile("text-settings", pageColour, Modifier.fillMaxWidth()) {
                Column {
                    PersonalizedSettingTitle("text size", "${(settings.textScale * 100).roundToInt()}%")
                    Slider(
                        value = settings.textScale,
                        onValueChange = onTextScaleChange,
                        valueRange = 0.9f..1.5f,
                        steps = 5,
                        colors = personalizedSliderColors(),
                    )
                }
            }
        }
        item {
            PersonalizedSettingToggle(
                seed = "reduce-motion",
                title = "reduce motion",
                description = "使用短淡化過場，減少大幅移動",
                pageColour = pageColour,
                checked = settings.reduceMotion,
                onChange = onReduceMotionChange,
            )
        }
        item {
            PersonalizedSettingToggle(
                seed = "contrast",
                title = "high contrast",
                description = "提高次要文字對比度",
                pageColour = pageColour,
                checked = settings.highContrast,
                onChange = onHighContrastChange,
            )
        }
        item {
            PersonalizedSettingToggle(
                seed = "location",
                title = "precise location",
                description = "使用精確定位及香港街區解析",
                pageColour = pageColour,
                checked = settings.preciseLocation,
                onChange = onPreciseLocationChange,
            )
        }
        item {
            PersonalizedSettingToggle(
                seed = "notifications",
                title = "weather notifications",
                description = "接收香港天文台警告、特別提示及已啟用的位置天氣通知",
                pageColour = pageColour,
                checked = settings.notificationsEnabled,
                onChange = onNotificationsChange,
            )
        }
        item {
            PersonalizedSettingToggle(
                seed = "notification-location-heavy-rain",
                title = "location heavy rain",
                description = "本機按所在地區過去60分鐘雨量 50 / 70 mm 門檻提示；位置不會上傳",
                pageColour = pageColour,
                checked = settings.locationHeavyRainNotificationsEnabled,
                onChange = onLocationHeavyRainNotificationsChange,
            )
        }
        item {
            PersonalizedSettingToggle(
                seed = "notification-personalized-rain",
                title = "rain approaching",
                description = "使用天文台 SWIRLS 預報本機判斷未來降雨及雨勢變化；位置只在裝置取樣，不會上傳",
                pageColour = pageColour,
                checked = settings.personalizedRainNotificationsEnabled,
                onChange = onPersonalizedRainNotificationsChange,
            )
        }
        item {
            MetroTile(
                "notification-diagnostics",
                pageColour,
                Modifier.fillMaxWidth(),
                onClick = onRefreshNotificationDiagnostics,
            ) {
                Column {
                    PersonalizedSettingTitle(
                        "notification diagnostics",
                        notificationDiagnostics.verdict.displayLabel(),
                    )
                    DiagnosticLine(
                        "periodic ${notificationDiagnostics.periodicActiveCount} active · " +
                            "dispatch 2D1 ${notificationDiagnostics.periodicDispatchHeavyRain.onOff()} / " +
                            "SWIRLS ${notificationDiagnostics.periodicDispatchPersonalizedRain.onOff()}",
                    )
                    DiagnosticLine(
                        "immediate ${notificationDiagnostics.immediateActiveCount} active · " +
                            "2D1 ${notificationDiagnostics.immediateDispatchHeavyRain.onOff()} / " +
                            "SWIRLS ${notificationDiagnostics.immediateDispatchPersonalizedRain.onOff()}",
                    )
                    DiagnosticLine(
                        "location ${notificationDiagnostics.locationDistrict.ifBlank { "unavailable" }} · " +
                            ageText(notificationDiagnostics.locationAgeMs),
                    )
                    DiagnosticLine(
                        "2D1 ${notificationDiagnostics.heavyRainStatus} · checked " +
                            eventAgeText(
                                notificationDiagnostics.checkedAtEpochMs,
                                notificationDiagnostics.heavyRainLastCheckedEpochMs,
                            ),
                    )
                    DiagnosticLine(
                        "SWIRLS ${notificationDiagnostics.personalizedRainStatus} · checked " +
                            eventAgeText(
                                notificationDiagnostics.checkedAtEpochMs,
                                notificationDiagnostics.personalizedRainLastCheckedEpochMs,
                            ),
                    )
                    DiagnosticLine(
                        "SWIRLS source " + eventAgeText(
                            notificationDiagnostics.checkedAtEpochMs,
                            notificationDiagnostics.personalizedRainLastSourceRunEpochMs,
                        ) + " · pending " + notificationDiagnostics.personalizedRainPendingKind.ifBlank { "none" },
                    )
                    if (notificationDiagnostics.error.isNotBlank()) {
                        DiagnosticLine("error ${notificationDiagnostics.error}")
                    }
                    Text(
                        "tap to refresh · diagnostics never exposes exact coordinates",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        item {
            MetroTile("notification-settings", pageColour, Modifier.fillMaxWidth(), onClick = onOpenNotificationSettings) {
                Column {
                    PersonalizedSettingTitle(
                        "system notification settings",
                        "檢查通知權限及各重要程度頻道是否已開啟",
                    )
                    Text("open settings ↗", color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp)
                }
            }
        }
        item {
            MetroTile("cache", pageColour, Modifier.fillMaxWidth(), onClick = onClearCache) {
                Column {
                    PersonalizedSettingTitle("clear cache", "移除離線天氣資料並重新同步")
                    Text("clear now", color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp)
                }
            }
        }
        item {
            Text(
                "Weather Metro ${BuildConfig.VERSION_NAME}\nWeather: Hong Kong Observatory first\nHourly estimates: Open-Meteo",
                color = LocalMetroSubText.current,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun PersonalizedSettingToggle(
    seed: String,
    title: String,
    description: String,
    pageColour: Color,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    MetroTile(seed, pageColour, Modifier.fillMaxWidth(), onClick = { onChange(!checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PersonalizedSettingTitle(title, description)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun PersonalizedSettingTitle(title: String, description: String) {
    Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
    Text(
        description,
        color = LocalMetroSubText.current,
        fontSize = 11.sp,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun DiagnosticLine(text: String) {
    Text(
        text = text,
        color = LocalMetroSubText.current,
        fontSize = 11.sp,
        modifier = Modifier.padding(bottom = 3.dp),
    )
}

private fun PersonalizedNotificationDiagnosticVerdict.displayLabel(): String = when (this) {
    PersonalizedNotificationDiagnosticVerdict.READY -> "ready · single shared cadence verified"
    PersonalizedNotificationDiagnosticVerdict.DISABLED -> "disabled · no active local cadence"
    PersonalizedNotificationDiagnosticVerdict.LOCATION_UNAVAILABLE -> "location unavailable"
    PersonalizedNotificationDiagnosticVerdict.LOCATION_STALE -> "location stale"
    PersonalizedNotificationDiagnosticVerdict.PERIODIC_MISSING -> "periodic work missing"
    PersonalizedNotificationDiagnosticVerdict.PERIODIC_DUPLICATE -> "duplicate periodic work detected"
    PersonalizedNotificationDiagnosticVerdict.PERIODIC_DISPATCH_INVALID -> "periodic dispatch flags invalid"
    PersonalizedNotificationDiagnosticVerdict.STOPPING_OR_STALE_WORK -> "disabled but work still active"
    PersonalizedNotificationDiagnosticVerdict.READ_ERROR -> "diagnostics read error"
}

private fun Boolean.onOff(): String = if (this) "on" else "off"

private fun ageText(ageMs: Long?): String {
    if (ageMs == null) return "age unknown"
    if (ageMs < 60_000L) return "<1m old"
    if (ageMs < 60 * 60_000L) return "${ageMs / 60_000L}m old"
    return "${ageMs / (60 * 60_000L)}h old"
}

private fun eventAgeText(checkedAtEpochMs: Long, eventEpochMs: Long): String {
    if (eventEpochMs <= 0L || checkedAtEpochMs <= 0L) return "never"
    val age = checkedAtEpochMs - eventEpochMs
    if (age < 0L) return "clock mismatch"
    return ageText(age).removeSuffix(" old") + " ago"
}

@Composable
private fun personalizedSliderColors() = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = Color.White,
    inactiveTrackColor = Color.Black.copy(alpha = 0.55f),
    activeTickColor = Color.Black.copy(alpha = 0.4f),
    inactiveTickColor = Color.White.copy(alpha = 0.5f),
)
