package com.weather.metro.data.rain

internal fun rainUserFacingError(rawMessage: String?): String {
    val raw = rawMessage.orEmpty().lowercase()
    return when {
        raw.contains("timeout") || raw.contains("timed out") -> "資料來源回應逾時"
        raw.contains("parser") ||
            raw.contains("parsing") ||
            raw.contains("parse ") ||
            raw.contains("json") ||
            raw.contains("xml") ||
            raw.contains("malformed") ||
            raw.contains("invalid payload") ||
            raw.contains("unexpected payload") -> "資料格式暫時無法讀取"
        raw.contains("http") ||
            raw.contains("network") ||
            raw.contains("connection") ||
            raw.contains("connect") ||
            raw.contains("unreachable") ||
            raw.contains("unknown host") ||
            raw.contains("unable to resolve host") -> "資料來源暫時無法連線"
        else -> "降雨資料暫時無法更新"
    }
}
