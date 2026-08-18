package com.weather.metro.data.tools

fun ToolEndpoints.rainSwirlsPointSeries(
    latitude: Double,
    longitude: Double,
): String {
    require(latitude in -90.0..90.0) { "Invalid latitude" }
    require(longitude in -180.0..180.0) { "Invalid longitude" }
    return "$RAIN_ORIGIN/api/rain/swirls/point-series?lat=$latitude&lon=$longitude"
}
