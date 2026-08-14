package com.weather.metro.data.tools

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StormLiveEndpointsTest {
    @Test
    fun `live proxy endpoints stay Worker scoped and source specific`() {
        val hkoList = ToolEndpoints.stormHkoListLive()
        val hkoTrack = ToolEndpoints.stormHkoTrackLive(
            "http://www.hko.gov.hk/wxinfo/currwx/hko_tctrack_2601.xml",
        )
        val cmaList = ToolEndpoints.stormCmaListLive(123L)
        val cmaDetail = ToolEndpoints.stormCmaDetailLive("2601", 123L)
        val jmaFeed = ToolEndpoints.stormJmaFeedLive()
        val jmaDoc = ToolEndpoints.stormJmaDocumentLive(
            "../data/20260814060000_0_VPTW60_2601.xml",
        )

        listOf(hkoList, hkoTrack, cmaList, cmaDetail, jmaFeed, jmaDoc).forEach { url ->
            assertTrue(url.startsWith("${ToolEndpoints.STORM_ORIGIN}/?url="))
        }
        assertTrue(hkoTrack.contains("https%3A%2F%2Fwww.hko.gov.hk"))
        assertTrue(cmaDetail.contains("view_2601"))
        assertTrue(jmaDoc.contains("VPTW60"))
    }

    @Test
    fun `live endpoint builders reject arbitrary proxy targets`() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolEndpoints.stormHkoTrackLive("https://example.com/wxinfo/currwx/hko_tctrack_2601.xml")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolEndpoints.stormHkoTrackLive("https://www.hko.gov.hk/anything.xml")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolEndpoints.stormCmaDetailLive("2601&url=https://example.com", 123L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolEndpoints.stormJmaDocumentLive("https://example.com/developer/xml/data/x_VPTW60_x.xml")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolEndpoints.stormJmaDocumentLive("https://www.data.jma.go.jp/developer/xml/feed/extra.xml")
        }
    }
}
