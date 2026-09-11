package com.weather.metro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weather.metro.BuildConfig
import com.weather.metro.data.settings.PageColourSlot
import com.weather.metro.data.settings.UiSettings
import com.weather.metro.notification.PersonalizedNotificationDiagnosticVerdict
import com.weather.metro.notification.PersonalizedNotificationDiagnostics
import com.weather.metro.ui.components.MetroGlassContextSurface
import com.weather.metro.ui.components.MetroSectionLabel
import com.weather.metro.ui.components.MetroTile
import com.weather.metro.ui.theme.LocalMetroSubText
import com.weather.metro.ui.theme.argbColor
import kotlin.math.roundToInt

private enum class FoldableSettingsSection(val label: String, val description: String) {
    APPEARANCE("appearance", "色彩、文字及動態效果"),
    NOTIFICATIONS("location & notifications", "定位及天氣通知"),
    SYSTEM("diagnostics & system", "通知診斷及快取"),
    ABOUT("about", "版本及資料來源"),
}

/**
 * Master-detail settings surface for unfolded devices and tablets.
 *
 * The left 35% is persistent section navigation. The right 65% contains only
 * the selected section so controls are not stretched into a single oversized
 * column. Compact devices continue to use the existing SettingsScreen.
 */
@Composable
fun FoldableSettingsScreen(
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
    onBackToWeather: () -> Unit,
) {
    var selectedSection by rememberSaveable { mutableStateOf(FoldableSettingsSection.APPEARANCE) }

    Row(modifier = Modifier.fillMaxSize()) {
        FoldableSettingsNavigation(
            selectedSection = selectedSection,
            pageColour = pageColour,
            onSelect = { selectedSection = it },
            onBackToWeather = onBackToWeather,
            modifier = Modifier
                .weight(0.35f)
                .fillMaxHeight(),
        )

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.10f)),
        )

        Box(
            modifier = Modifier
                .weight(0.65f)
                .fillMaxHeight(),
        ) {
            when (selectedSection) {
                FoldableSettingsSection.APPEARANCE -> FoldableAppearanceSettings(
                    settings = settings,
                    pageColour = pageColour,
                    onPageColourChange = onPageColourChange,
                    onTextScaleChange = onTextScaleChange,
                    onReduceMotionChange = onReduceMotionChange,
                    onHighContrastChange = onHighContrastChange,
                )
                FoldableSettingsSection.NOTIFICATIONS -> FoldableNotificationSettings(
                    settings = settings,
                    pageColour = pageColour,
                    onPreciseLocationChange = onPreciseLocationChange,
                    onNotificationsChange = onNotificationsChange,
                    onLocationHeavyRainNotificationsChange = onLocationHeavyRainNotificationsChange,
                    onPersonalizedRainNotificationsChange = onPersonalizedRainNotificationsChange,
                )
                FoldableSettingsSection.SYSTEM -> FoldableSystemSettings(
                    diagnostics = notificationDiagnostics,
                    pageColour = pageColour,
                    onRefreshNotificationDiagnostics = onRefreshNotificationDiagnostics,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onClearCache = onClearCache,
                )
                FoldableSettingsSection.ABOUT -> FoldableAboutSettings()
            }
        }
    }
}

@Composable
private fun FoldableSettingsNavigation(
    selectedSection: FoldableSettingsSection,
    pageColour: Color,
    onSelect: (FoldableSettingsSection) -> Unit,
    onBackToWeather: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 22.dp, top = 4.dp, end = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Text(
                "settings",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Light,
            )
        }
        item {
            MetroGlassContextSurface(
                accent = pageColour,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBackToWeather),
            ) {
                Text(
                    "‹ weather",
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        items(FoldableSettingsSection.entries.size) { index ->
            val section = FoldableSettingsSection.entries[index]
            MetroGlassContextSurface(
                accent = if (selectedSection == section) pageColour else Color.White.copy(alpha = 0.12f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(section) },
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
                    Text(
                        text = section.label,
                        color = Color.White.copy(alpha = if (selectedSection == section) 1f else 0.82f),
                        fontSize = 17.sp,
                        fontWeight = if (selectedSection == section) FontWeight.Medium else FontWeight.Light,
                    )
                    Text(
                        text = section.description,
                        color = LocalMetroSubText.current,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FoldableAppearanceSettings(
    settings: UiSettings,
    pageColour: Color,
    onPageColourChange: (PageColourSlot, Long) -> Unit,
    onTextScaleChange: (Float) -> Unit,
    onReduceMotionChange: (Boolean) -> Unit,
    onHighContrastChange: (Boolean) -> Unit,
) {
    val accents = listOf(0xFF1BA1E2, 0xFF00A300, 0xFFA200FF, 0xFFE671B8, 0xFFF09609, 0xFFE51400)
    var selectedPage by rememberSaveable { mutableStateOf(PageColourSlot.CURRENT) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item { MetroSectionLabel("appearance") }
        item {
            MetroTile("foldable-page-colours", pageColour, Modifier.fillMaxWidth()) {
                Column {
                    FoldableSettingTitle("page accents", "為每個主要頁面設定局部強調色")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PageColourSlot.entries.forEach { slot ->
                            SettingsPageChip(
                                label = slot.label,
                                accent = argbColor(settings.pageColours.colour(slot)),
                                selected = selectedPage == slot,
                                modifier = Modifier.weight(1f),
                                onClick = { selectedPage = slot },
                            )
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
                            SettingsAccentSwatch(
                                accent = argbColor(value),
                                selected = settings.pageColours.colour(selectedPage) == value,
                                onClick = { onPageColourChange(selectedPage, value) },
                            )
                        }
                    }
                }
            }
        }
        item {
            MetroTile("foldable-text-settings", pageColour, Modifier.fillMaxWidth()) {
                Column {
                    FoldableSettingTitle("text size", "${(settings.textScale * 100).roundToInt()}%")
                    SettingsGlassSlider(
                        value = settings.textScale,
                        onValueChange = onTextScaleChange,
                        accent = pageColour,
                    )
                }
            }
        }
        item {
            FoldableSettingToggle(
                seed = "foldable-reduce-motion",
                title = "reduce motion",
                description = "使用短淡化過場，減少大幅移動",
                pageColour = pageColour,
                checked = settings.reduceMotion,
                onChange = onReduceMotionChange,
            )
        }
        item {
            FoldableSettingToggle(
                seed = "foldable-contrast",
                title = "high contrast",
                description = "提高次要文字對比度",
                pageColour = pageColour,
                checked = settings.highContrast,
                onChange = onHighContrastChange,
            )
        }
    }
}

@Composable
private fun FoldableNotificationSettings(
    settings: UiSettings,
    pageColour: Color,
    onPreciseLocationChange: (Boolean) -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onLocationHeavyRainNotificationsChange: (Boolean) -> Unit,
    onPersonalizedRainNotificationsChange: (Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item { MetroSectionLabel("location & notifications") }
        item {
            FoldableSettingToggle(
                seed = "foldable-location",
                title = "precise location",
                description = "使用精確定位及香港街區解析",
                pageColour = pageColour,
                checked = settings.preciseLocation,
                onChange = onPreciseLocationChange,
            )
        }
        item {
            FoldableSettingToggle(
                seed = "foldable-notifications",
                title = "weather notifications",
                description = "接收香港天文台警告、特別提示及已啟用的位置天氣通知",
                pageColour = pageColour,
                checked = settings.notificationsEnabled,
                onChange = onNotificationsChange,
            )
        }
        item {
            FoldableSettingToggle(
                seed = "foldable-heavy-rain",
                title = "location heavy rain",
                description = "本機按所在地區過去60分鐘雨量 50 / 70 mm 門檻提示；位置不會上傳",
                pageColour = pageColour,
                checked = settings.locationHeavyRainNotificationsEnabled,
                onChange = onLocationHeavyRainNotificationsChange,
            )
        }
        item {
            FoldableSettingToggle(
                seed = "foldable-rain-approaching",
                title = "rain approaching",
                description = "使用天文台 SWIRLS 預報本機判斷未來降雨及雨勢變化；位置只在裝置取樣，不會上傳",
                pageColour = pageColour,
                checked = settings.personalizedRainNotificationsEnabled,
                onChange = onPersonalizedRainNotificationsChange,
            )
        }
    }
}

@Composable
private fun FoldableSystemSettings(
    diagnostics: PersonalizedNotificationDiagnostics,
    pageColour: Color,
    onRefreshNotificationDiagnostics: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onClearCache: () -> Unit,
) {
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item { MetroSectionLabel("diagnostics & system") }
        item {
            MetroTile("foldable-notification-diagnostics", pageColour, Modifier.fillMaxWidth()) {
                Column {
                    FoldableSettingTitle(
                        "notification diagnostics",
                        diagnostics.verdict.foldableDisplayLabel(),
                    )
                    FoldableDiagnosticLine(
                        "HKO ${if (diagnostics.officialError.isBlank()) "synced" else "attention"} · " +
                            "location ${diagnostics.locationDistrict.ifBlank { "unavailable" }}",
                    )
                    Row(
                        modifier = Modifier.padding(top = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsActionBadge(
                            text = if (diagnosticsExpanded) "收起" else "詳情",
                            accent = pageColour,
                            minWidth = 64.dp,
                            onClick = { diagnosticsExpanded = !diagnosticsExpanded },
                        )
                        SettingsActionBadge(
                            text = "refresh diagnostics",
                            accent = pageColour,
                            minWidth = 132.dp,
                            onClick = onRefreshNotificationDiagnostics,
                        )
                    }
                    if (diagnosticsExpanded) {
                        Spacer(Modifier.height(10.dp))
                        FoldableDiagnosticLine(
                            "HKO journal work periodic ${diagnostics.officialPeriodicActiveCount} / " +
                                "immediate ${diagnostics.officialImmediateActiveCount}",
                        )
                        FoldableDiagnosticLine(
                            "periodic ${diagnostics.periodicActiveCount} active · " +
                                "2D1 ${diagnostics.periodicDispatchHeavyRain.foldableOnOff()} / " +
                                "SWIRLS ${diagnostics.periodicDispatchPersonalizedRain.foldableOnOff()}",
                        )
                        FoldableDiagnosticLine(
                            "immediate ${diagnostics.immediateActiveCount} active · " +
                                "2D1 ${diagnostics.immediateDispatchHeavyRain.foldableOnOff()} / " +
                                "SWIRLS ${diagnostics.immediateDispatchPersonalizedRain.foldableOnOff()}",
                        )
                        FoldableDiagnosticLine(
                            "location ${diagnostics.locationDistrict.ifBlank { "unavailable" }} · " +
                                foldableAgeText(diagnostics.locationAgeMs),
                        )
                        FoldableDiagnosticLine(
                            "2D1 ${diagnostics.heavyRainStatus} · SWIRLS ${diagnostics.personalizedRainStatus}",
                        )
                        if (diagnostics.officialError.isNotBlank()) {
                            FoldableDiagnosticLine("HKO journal error ${diagnostics.officialError}")
                        }
                        if (diagnostics.error.isNotBlank()) {
                            FoldableDiagnosticLine("error ${diagnostics.error}")
                        }
                        Text(
                            "diagnostics never exposes exact coordinates",
                            color = Color.White.copy(alpha = 0.68f),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        item {
            MetroTile(
                "foldable-notification-settings",
                pageColour,
                Modifier.fillMaxWidth(),
                onClick = onOpenNotificationSettings,
            ) {
                Column {
                    FoldableSettingTitle(
                        "system notification settings",
                        "檢查通知權限及各重要程度頻道是否已開啟",
                    )
                    SettingsActionBadge("open settings ↗", pageColour)
                }
            }
        }
        item {
            MetroTile(
                "foldable-cache",
                pageColour,
                Modifier.fillMaxWidth(),
                onClick = onClearCache,
            ) {
                Column {
                    FoldableSettingTitle("clear cache", "移除離線天氣資料並重新同步")
                    SettingsActionBadge("clear now", pageColour)
                }
            }
        }
    }
}

@Composable
private fun FoldableAboutSettings() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item { MetroSectionLabel("about") }
        item {
            Text(
                "Weather Metro ${BuildConfig.VERSION_NAME}\nWeather: Hong Kong Observatory first\nHourly estimates: Open-Meteo",
                color = LocalMetroSubText.current,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun FoldableSettingToggle(
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
                FoldableSettingTitle(title, description)
            }
            SettingsGlassToggle(
                checked = checked,
                accent = pageColour,
                onCheckedChange = onChange,
            )
        }
    }
}

@Composable
private fun FoldableSettingTitle(title: String, description: String) {
    Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
    Text(
        description,
        color = LocalMetroSubText.current,
        fontSize = 11.sp,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun FoldableDiagnosticLine(text: String) {
    Text(
        text = text,
        color = LocalMetroSubText.current,
        fontSize = 11.sp,
        modifier = Modifier.padding(bottom = 3.dp),
    )
}

private fun PersonalizedNotificationDiagnosticVerdict.foldableDisplayLabel(): String = when (this) {
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

private fun Boolean.foldableOnOff(): String = if (this) "on" else "off"

private fun foldableAgeText(ageMs: Long?): String {
    if (ageMs == null) return "age unknown"
    if (ageMs < 60_000L) return "<1m old"
    if (ageMs < 60 * 60_000L) return "${ageMs / 60_000L}m old"
    return "${ageMs / (60 * 60_000L)}h old"
}
