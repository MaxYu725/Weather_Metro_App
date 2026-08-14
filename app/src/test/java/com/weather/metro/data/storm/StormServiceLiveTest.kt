package com.weather.metro.data.storm

import com.weather.metro.domain.storm.StormAgency
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StormServiceLiveTest {
    @Test
    fun `CWA public result uses advisory initial time not future valid time`() = runBlocking {
        val loader = StormLiveLoader(
            transport = object : StormLiveHttpTransport {
                override suspend fun getText(url: String, accept: String, timeoutMs: Int): String = CWA_JSON
            },
        )
        val service = StormService(liveLoader = loader)

        val result = service.loadLiveAgency(StormAgency.CWA)

        assertEquals("2026-08-14T06:00:00Z", result.updatedAt)
        assertEquals("2026-08-14T06:00:00Z", result.storms.single().bulletinTime)
        assertEquals("2026-08-15T06:00:00Z", result.storms.single().forecastPoints.single().validAt)
    }

    companion object {
        private val CWA_JSON = """
            {
              "success": true,
              "records": {
                "TropicalCyclones": {
                  "TropicalCyclone": [{
                    "Year": "2026",
                    "CwaTyNo": "2601",
                    "TyphoonName": "ALPHA",
                    "CwaTyphoonName": "阿爾法",
                    "AnalysisData": {"Fix": [{
                      "DateTime": "2026-08-14T06:00:00Z",
                      "CoordinateLongitude": 118.5,
                      "CoordinateLatitude": 22.1,
                      "MaxWindSpeed": 35
                    }]},
                    "ForecastData": {"Fix": [{
                      "InitialTime": "2026-08-14T06:00:00Z",
                      "ForecastHour": 24,
                      "CoordinateLongitude": 117.0,
                      "CoordinateLatitude": 23.0,
                      "MaxWindSpeed": 28
                    }]}
                  }]
                }
              }
            }
        """.trimIndent()
    }
}
