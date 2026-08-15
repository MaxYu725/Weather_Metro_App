package com.weather.metro.ui.rain

internal const val FORECAST_BITMAP_BYTES_PER_PIXEL = 4L
internal const val FORECAST_BITMAP_CACHE_BUDGET_BYTES = 2L * 1024L * 1024L

internal fun forecastBitmapBytesPerFrame(rows: Int, cols: Int): Long {
    require(rows > 0 && cols > 0) { "Forecast raster dimensions must be positive" }
    return rows.toLong() * cols.toLong() * FORECAST_BITMAP_BYTES_PER_PIXEL
}

internal fun forecastBitmapCacheCapacity(
    rows: Int,
    cols: Int,
    frameCount: Int,
    budgetBytes: Long = FORECAST_BITMAP_CACHE_BUDGET_BYTES,
): Int {
    require(frameCount > 0) { "Forecast frame count must be positive" }
    require(budgetBytes > 0) { "Forecast bitmap cache budget must be positive" }
    val bytesPerFrame = forecastBitmapBytesPerFrame(rows, cols)
    val budgetCapacity = (budgetBytes / bytesPerFrame).coerceAtLeast(1L)
    return minOf(frameCount.toLong(), budgetCapacity).toInt()
}

internal fun forecastBitmapCacheEstimatedBytes(
    rows: Int,
    cols: Int,
    cachedFrameCount: Int,
): Long {
    require(cachedFrameCount >= 0) { "Cached Forecast frame count cannot be negative" }
    return forecastBitmapBytesPerFrame(rows, cols) * cachedFrameCount.toLong()
}
