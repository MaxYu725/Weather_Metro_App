from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1))


root = Path("app/src/main/java/com/weather/metro/ui/WeatherMetroRoot.kt")
home = Path("app/src/main/java/com/weather/metro/ui/screens/HomeCurrentScreen.kt")

replace_once(
    root,
    "        val activePageColour = argbColor(settings.pageColours.colour(activePage))\n",
    "",
)

replace_once(
    root,
    '''                ) {
                    val showWeatherProgress = pageRequiresWeatherData(activePage) && (
                        loadState is WeatherLoadState.Loading ||
                            (loadState as? WeatherLoadState.Ready)?.refreshing == true
                        )
                    Column {
                        if (showWeatherProgress) {
                            MetroProgress(colour = activePageColour)
                        } else {
                            Spacer(Modifier.height(10.dp))
                        }
                        PivotHeader(
                            current = activePage.label,
                            next = pages[(pageIndex + 1) % pages.size].label,
                            reduceMotion = reduceMotion,
                        )
                    }
                }
''',
    '''                ) {
                    Column {
                        PrimaryDataStatus(loadState)
                        PivotHeader(
                            current = activePage.label,
                            next = pages[(pageIndex + 1) % pages.size].label,
                            reduceMotion = reduceMotion,
                        )
                    }
                }
''',
)

replace_once(
    root,
    '''                                        pageColour = pageColour,
                                        refreshing = state.refreshing,
                                        onRefresh = viewModel::refresh,
                                        onRequestLocation = requestLocationPermission,
''',
    '''                                        pageColour = pageColour,
                                        onRequestLocation = requestLocationPermission,
''',
)

pivot_marker = '''@Composable
private fun PivotHeader(current: String, next: String, reduceMotion: Boolean) {
'''
status_composable = '''@Composable
private fun PrimaryDataStatus(state: WeatherLoadState) {
    val statusText: String
    val statusColour: Color
    when (state) {
        WeatherLoadState.Loading -> {
            statusText = "正在取得香港天文台資料"
            statusColour = Color(0xFF8A8A8A)
        }
        is WeatherLoadState.Error -> {
            statusText = if (state.cached != null) "資料更新失敗 · 顯示快取" else "香港天文台資料暫時無法更新"
            statusColour = if (state.cached != null) Color(0xFFF09609) else Color(0xFFE51400)
        }
        is WeatherLoadState.Ready -> when {
            state.refreshing -> {
                statusText = "正在更新香港天文台資料"
                statusColour = Color(0xFF8A8A8A)
            }
            state.snapshot.isStale -> {
                statusText = "顯示離線快取"
                statusColour = Color(0xFFF09609)
            }
            else -> {
                statusText = "香港天文台資料已同步"
                statusColour = Color(0xFF00C853)
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .padding(start = 22.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).height(6.dp).background(statusColour))
        Spacer(Modifier.width(8.dp))
        Text(
            text = statusText,
            color = LocalMetroSubText.current,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

'''
replace_once(root, pivot_marker, status_composable + pivot_marker)

replace_once(home, "import com.weather.metro.ui.components.MetroProgress\n", "")
replace_once(
    home,
    '''    pageColour: Color,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onRequestLocation: () -> Unit,
''',
    '''    pageColour: Color,
    onRequestLocation: () -> Unit,
''',
)

sync_item = '''        item {
            val syncAccent = if (snapshot.isStale) Color(0xFFF09609) else pageColour
            MetroGlassContextSurface(
                accent = syncAccent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                if (refreshing) {
                    Box(Modifier.width(54.dp)) { MetroProgress(colour = pageColour) }
                } else {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(if (snapshot.isStale) Color(0xFFF09609) else Color(0xFF00C853)),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = when {
                        refreshing -> "正在更新香港天文台資料"
                        snapshot.isStale -> "顯示離線快取"
                        else -> "香港天文台資料已同步"
                    },
                    color = LocalMetroSubText.current,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (refreshing) "updating" else "refresh",
                    color = pageColour,
                    fontSize = 13.sp,
                    modifier = if (refreshing) Modifier else Modifier.clickable(onClick = onRefresh),
                )
                }
            }
        }

'''
replace_once(home, sync_item, "")
replace_once(home, "    val localForecastIndex = if (hasActiveAlerts) 4 else 2\n", "    val localForecastIndex = if (hasActiveAlerts) 3 else 1\n")
replace_once(home, "                    if (it) scrollToItem(1)\n", "                    if (it) scrollToItem(0)\n")
replace_once(home, "                ) { scrollToItem(3) }\n", "                ) { scrollToItem(2) }\n")
replace_once(home, "                ) { scrollToItem(8) }\n", "                ) { scrollToItem(7) }\n")
