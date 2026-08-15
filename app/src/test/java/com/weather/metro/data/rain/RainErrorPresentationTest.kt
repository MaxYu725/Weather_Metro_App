package com.weather.metro.data.rain

import org.junit.Assert.assertEquals
import org.junit.Test

class RainErrorPresentationTest {
    @Test
    fun timeoutErrorsAreUserFacing() {
        assertEquals("資料來源回應逾時", rainUserFacingError("Socket timeout while reading"))
    }

    @Test
    fun parserErrorsAreUserFacing() {
        assertEquals("資料格式暫時無法讀取", rainUserFacingError("JSON parsing failed"))
    }

    @Test
    fun networkErrorsAreUserFacing() {
        assertEquals("資料來源暫時無法連線", rainUserFacingError("Unable to resolve host radar.example"))
    }

    @Test
    fun unknownTechnicalErrorsDoNotLeakRawText() {
        assertEquals("降雨資料暫時無法更新", rainUserFacingError("Illegal state at frame 7"))
    }
}
