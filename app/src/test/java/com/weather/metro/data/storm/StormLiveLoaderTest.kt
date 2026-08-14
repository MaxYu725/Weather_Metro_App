package com.weather.metro.data.storm

import com.weather.metro.data.tools.ToolEndpoints
import com.weather.metro.domain.storm.StormAgency
import com.weather.metro.domain.storm.StormLiveState
import com.weather.metro.domain.storm.StormPointType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StormLiveLoaderTest {
    @Test
    fun `all four agencies normalize independently`() = runBlocking {
        val loader = StormLiveLoader(
            transport = FixtureTransport(),
            nowMillis = { 123L },
        )

        val results = loader.loadAll().associateBy { it.agency }

        assertEquals(4, results.size)
        StormAgency.entries.forEach { agency ->
            assertEquals(StormLiveState.OK, results.getValue(agency).state)
            assertEquals(1, results.getValue(agency).storms.size)
        }

        val hko = results.getValue(StormAgency.HKO).storms.single()
        assertEquals("ALPHA", hko.nameEn)
        assertEquals("阿爾法", hko.nameZh)
        assertEquals(2, hko.analysisPoints.size)
        assertEquals(1, hko.forecastPoints.size)
        assertEquals(StormPointType.FORECAST, hko.forecastPoints.single().pointType)

        val cma = results.getValue(StormAgency.CMA).storms.single()
        assertEquals(1, cma.analysisPoints.size)
        assertEquals(1, cma.forecastPoints.size)
        assertEquals(24, cma.forecastPoints.single().forecastHour)
        assertEquals(1, cma.analysisPoints.single().windRadii.size)
        assertEquals(100.0, cma.analysisPoints.single().windRadii.single().northEastKm, 0.0001)

        val jma = results.getValue(StormAgency.JMA).storms.single()
        assertEquals("2601", jma.internationalNumber)
        assertEquals(1, jma.analysisPoints.size)
        assertEquals(1, jma.forecastPoints.size)
        assertEquals(92.6, jma.forecastPoints.single().probabilityRadiusKm!!, 0.001)

        val cwa = results.getValue(StormAgency.CWA).storms.single()
        assertEquals(1, cwa.analysisPoints.size)
        assertEquals(1, cwa.forecastPoints.size)
        assertEquals(24, cwa.forecastPoints.single().forecastHour)
        assertEquals(80.0, cwa.forecastPoints.single().probabilityRadiusKm!!, 0.0001)
        assertEquals(2, cwa.analysisPoints.single().windRadii.size)
    }

    @Test
    fun `one agency failure never blocks the other agencies`() = runBlocking {
        val loader = StormLiveLoader(
            transport = FixtureTransport(failJma = true),
            nowMillis = { 123L },
        )

        val results = loader.loadAll().associateBy { it.agency }

        assertEquals(StormLiveState.ERROR, results.getValue(StormAgency.JMA).state)
        assertTrue(results.getValue(StormAgency.JMA).storms.isEmpty())
        assertEquals(StormLiveState.OK, results.getValue(StormAgency.HKO).state)
        assertEquals(StormLiveState.OK, results.getValue(StormAgency.CMA).state)
        assertEquals(StormLiveState.OK, results.getValue(StormAgency.CWA).state)
    }

    @Test
    fun `live transport preserves 16 second timeout and source accept type`() = runBlocking {
        val transport = FixtureTransport()
        val loader = StormLiveLoader(transport = transport, nowMillis = { 123L })

        loader.loadAgency(StormAgency.CWA)
        loader.loadAgency(StormAgency.HKO)

        assertTrue(transport.requests.all { it.timeoutMs == 16_000 })
        assertTrue(transport.requests.any { it.url == ToolEndpoints.stormCwaLive() && it.accept.contains("application/json") })
        assertTrue(transport.requests.any { it.url == ToolEndpoints.stormHkoListLive() && it.accept.contains("application/xml") })
    }

    private class FixtureTransport(
        private val failJma: Boolean = false,
    ) : StormLiveHttpTransport {
        val requests = mutableListOf<Request>()

        override suspend fun getText(url: String, accept: String, timeoutMs: Int): String {
            requests += Request(url, accept, timeoutMs)
            return when {
                url == ToolEndpoints.stormCwaLive() -> CWA_JSON
                url.contains("tc_list.xml") -> HKO_LIST_XML
                url.contains("hko_tctrack_2601.xml") -> HKO_TRACK_XML
                url.contains("list_default") -> CMA_LIST_JSONP
                url.contains("view_2601") -> CMA_DETAIL_JSONP
                url.contains("extra.xml") || url.contains("extra_l.xml") -> {
                    if (failJma) error("synthetic JMA failure")
                    JMA_FEED_XML
                }
                url.contains("VPTW60") -> {
                    if (failJma) error("synthetic JMA failure")
                    JMA_TRACK_XML
                }
                else -> error("Unexpected fixture URL: $url")
            }
        }
    }

    private data class Request(
        val url: String,
        val accept: String,
        val timeoutMs: Int,
    )

    companion object {
        private val HKO_LIST_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <TropicalCycloneList>
              <TropicalCyclone>
                <TropicalCycloneID>2601</TropicalCycloneID>
                <TropicalCycloneChineseName>阿爾法</TropicalCycloneChineseName>
                <TropicalCycloneEnglishName>ALPHA</TropicalCycloneEnglishName>
                <TropicalCycloneURL>http://www.hko.gov.hk/wxinfo/currwx/hko_tctrack_2601.xml</TropicalCycloneURL>
              </TropicalCyclone>
            </TropicalCycloneList>
        """.trimIndent()

        private val HKO_TRACK_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <WeatherReport>
              <BulletinTime>2026-08-14T06:00:00Z</BulletinTime>
              <TropicalCycloneName>ALPHA</TropicalCycloneName>
              <PastInformation>
                <Time>2026-08-14T00:00:00Z</Time><Latitude>21.5N</Latitude><Longitude>119.0E</Longitude>
                <Intensity>TY</Intensity><MaximumWind>35 m/s</MaximumWind><CentralPressure>965 hPa</CentralPressure>
              </PastInformation>
              <AnalysisInformation>
                <Time>2026-08-14T06:00:00Z</Time><Latitude>22.1N</Latitude><Longitude>118.5E</Longitude>
                <Intensity>TY</Intensity><MaximumWind>35 m/s</MaximumWind><CentralPressure>965 hPa</CentralPressure>
              </AnalysisInformation>
              <ForecastInformation>
                <Time>2026-08-15T06:00:00Z</Time><Latitude>23.0N</Latitude><Longitude>117.0E</Longitude>
                <Intensity>STS</Intensity><MaximumWind>28 m/s</MaximumWind><CentralPressure>980 hPa</CentralPressure>
              </ForecastInformation>
            </WeatherReport>
        """.trimIndent()

        private val CMA_LIST_JSONP = """
            typhoon_jsons_list_default({"typhoonList":[["2601","ALPHA","阿爾法",null,null,null,null,"start"]]})
        """.trimIndent()

        private val CMA_DETAIL_JSONP = """
            typhoon_jsons_view_2601({"typhoon":[null,null,null,null,null,null,null,null,[
              [null,"202608140600",null,"TY",118.5,22.1,965,35,null,null,[["7",100,90,80,70]],{"BABJ":[[24,"202608140600",117.0,23.0,980,28,null,"STS"]]}]
            ]]})
        """.trimIndent()

        private val JMA_FEED_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>台風解析・予報情報</title>
                <updated>2026-08-14T06:00:00Z</updated>
                <link href="https://www.data.jma.go.jp/developer/xml/data/20260814060000_0_VPTW60_2601.xml" />
              </entry>
            </feed>
        """.trimIndent()

        private val JMA_TRACK_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Report xmlns="urn:jma">
              <Head><EventID>TC2601</EventID><ReportDateTime>2026-08-14T06:00:00Z</ReportDateTime></Head>
              <Body>
                <TyphoonNamePart><Name>ALPHA</Name><Number>2601</Number></TyphoonNamePart>
                <MeteorologicalInfo>
                  <DateTime type="実況">2026-08-14T06:00:00Z</DateTime>
                  <Area><Name>台風第1号</Name></Area>
                  <CenterPart><Coordinate type="中心位置（度）">+22.1+118.5/</Coordinate></CenterPart>
                  <Pressure unit="hPa">965</Pressure>
                  <WindSpeed type="最大風速" unit="m/s">35</WindSpeed>
                  <IntensityClass>TY</IntensityClass>
                </MeteorologicalInfo>
                <MeteorologicalInfo>
                  <DateTime type="予報 24時間後">2026-08-15T06:00:00Z</DateTime>
                  <ProbabilityCircle>
                    <BasePoint type="中心位置（度）">+23.0+117.0/</BasePoint>
                    <Radius unit="海里">50</Radius>
                  </ProbabilityCircle>
                  <Pressure unit="hPa">980</Pressure>
                  <WindSpeed type="最大風速" unit="m/s">28</WindSpeed>
                  <StormClass>STS</StormClass>
                </MeteorologicalInfo>
              </Body>
            </Report>
        """.trimIndent()

        private val CWA_JSON = """
            {
              "success": true,
              "records": {
                "TropicalCyclones": {
                  "TropicalCyclone": [{
                    "Year": "2026",
                    "CwaTdNo": "01",
                    "CwaTyNo": "2601",
                    "TyphoonName": "ALPHA",
                    "CwaTyphoonName": "阿爾法",
                    "AnalysisData": {"Fix": [{
                      "DateTime": "2026-08-14T06:00:00Z",
                      "CoordinateLongitude": 118.5,
                      "CoordinateLatitude": 22.1,
                      "MaxWindSpeed": 35,
                      "MaxGustSpeed": 45,
                      "Pressure": 965,
                      "MovingSpeed": 15,
                      "MovingDirection": "WNW",
                      "Circle15ms": {"Radius": 100, "QuadrantRadii": {"Radius": [
                        {"dir":"NE","value":110},{"dir":"SE","value":100},{"dir":"SW","value":90},{"dir":"NW","value":105}
                      ]}},
                      "Circle25ms": {"Radius": 45}
                    }]},
                    "ForecastData": {"Fix": [{
                      "InitialTime": "2026-08-14T06:00:00Z",
                      "ForecastHour": 24,
                      "CoordinateLongitude": 117.0,
                      "CoordinateLatitude": 23.0,
                      "MaxWindSpeed": 28,
                      "Pressure": 980,
                      "Radius70PercentProbability": 80,
                      "MovingPrediction": [{"lang":"zh-TW","value":"向西北移動"}],
                      "StateTransfer": [{"lang":"zh-TW","value":"逐漸減弱"}]
                    }]}
                  }]
                }
              }
            }
        """.trimIndent()
    }
}
