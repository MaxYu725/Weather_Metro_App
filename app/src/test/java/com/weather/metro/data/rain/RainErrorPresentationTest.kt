package com.weather.metro.data.rain

import kotlin.test.Test
import kotlin.test.assertEquals

class RainErrorPresentationTest {
    @Test
    fun `timeout errors are user facing`() {
        assertEquals("資料來源回應逾時", rainUserFacingError("Socket timeout while reading"))
    }

    @Test
    fun `parser errors are user facing`() {
        assertEquals("資料格式暫時無法讀取", rainUserFacingError("JSON parsing failed"))
    }

    @Test
    fun `network errors are user facing`() {
        assertEquals("資料來源暫時無法連線", rainUserFacingError("Unable to resolve host radar.example"))
    }

    @Test
    fun `unknown technical errors do not leak raw text`() {
        assertEquals("降雨資料暫時無法更新", rainUserFacingError("Illegal state at frame 7"))
    }
}
