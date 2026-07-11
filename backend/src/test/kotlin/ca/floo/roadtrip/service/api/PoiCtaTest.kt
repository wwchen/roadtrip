package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.domain.PoiDetailRow
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoiCtaTest {
    // Fixed clock so the dated Aspira deeplink is byte-stable. 2026-06-17
    // 14:23:45 UTC is 10:23:45 in America/New_York (no DST hop in June).
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-06-17T14:23:45Z"), ZoneId.of("UTC"))
    private val cta = PoiCta(clock = fixedClock)

    @Test
    fun `recgov reservable provider_ref produces booking URL`() {
        val out = cta.computeCta(row(providerRefJson = """{"recgov_id":"232450"}"""))
        assertEquals("https://www.recreation.gov/camping/campgrounds/232450", out?.url)
        assertEquals("Reserve on recreation.gov", out?.label)
        assertEquals("reserve", out?.kind)
    }

    @Test
    fun `reservecalifornia provider_ref produces park deeplink`() {
        val out = cta.computeCta(row(providerRefJson = """{"place_id":660,"facility_ids":[901]}"""))
        assertEquals("https://reservecalifornia.com/park/660", out?.url)
        assertEquals("Reserve on ReserveCalifornia", out?.label)
        assertEquals("reserve", out?.kind)
    }

    @Test
    fun `aspira parks canada produces dated NextGen deeplink with tenant label`() {
        val out =
            cta.computeCta(
                row(
                    providerRefJson = """{"transactionLocationId":4189,"mapId":-2147483361,"resourceLocationId":-2147483408}""",
                    infoUrl = "https://reservation.pc.gc.ca/",
                ),
            )
        val url = out?.url
        assertNotNull(url)
        assertTrue(url.startsWith("https://reservation.pc.gc.ca/create-booking/results?"), "host + path: $url")
        assertTrue(url.contains("transactionLocationId=4189"), url)
        assertTrue(url.contains("mapId=-2147483361"), url)
        assertTrue(url.contains("resourceLocationId=-2147483408"), url)
        assertTrue(url.contains("startDate=2026-06-17"), url)
        assertTrue(url.contains("endDate=2026-06-18"), url)
        assertEquals("Reserve on parks.canada.ca", out.label)
        assertEquals("reserve", out.kind)
    }

    @Test
    fun `aspira parks canada can derive CTA host from canonical reserve_url`() {
        val out =
            cta.computeCta(
                row(
                    providerRefJson = """{"transactionLocationId":4189,"mapId":-2147483361,"resourceLocationId":-2147483408}""",
                    reserveUrl = "https://reservation.pc.gc.ca/",
                ),
            )
        val url = out?.url
        assertNotNull(url)
        assertTrue(url.startsWith("https://reservation.pc.gc.ca/create-booking/results?"), "host + path: $url")
        assertTrue(url.contains("transactionLocationId=4189"), url)
        assertEquals("Reserve on parks.canada.ca", out.label)
        assertEquals("reserve", out.kind)
    }

    @Test
    fun `aspira CTA uses linked reservable map when POI map is only a container`() {
        val out =
            cta.computeCta(
                row(
                    providerRefJson = """{"transactionLocationId":4189,"mapId":-2147483026,"resourceLocationId":-2147483640}""",
                    ctaProviderRefJson = """{"transactionLocationId":4189,"mapId":-2147483645,"resourceLocationId":-2147483640}""",
                    infoUrl = "https://reservation.pc.gc.ca/",
                ),
            )
        val url = out?.url
        assertNotNull(url)
        assertTrue(url.contains("mapId=-2147483645"), url)
        assertTrue(!url.contains("mapId=-2147483026"), url)
    }

    @Test
    fun `aspira BC parks gets BC Parks label`() {
        val out =
            cta.computeCta(
                row(
                    providerRefJson = """{"transactionLocationId":1,"mapId":2,"resourceLocationId":null}""",
                    infoUrl = "https://camping.bcparks.ca/",
                ),
            )
        assertEquals("Book on BC Parks", out?.label)
        assertTrue(out!!.url.startsWith("https://camping.bcparks.ca/create-booking/results?"))
    }

    @Test
    fun `aspira WA state parks gets WA label`() {
        val out =
            cta.computeCta(
                row(
                    providerRefJson = """{"transactionLocationId":1,"mapId":2,"resourceLocationId":null}""",
                    infoUrl = "https://washington.goingtocamp.com/",
                ),
            )
        assertEquals("Book WA State Park", out?.label)
    }

    @Test
    fun `aspira without resourceLocationId omits it from the URL`() {
        // The string "NULL" or omitting when the tenant requires it bounces
        // WA's results page back to the homepage. We omit cleanly when null.
        val out =
            cta.computeCta(
                row(
                    providerRefJson = """{"transactionLocationId":1,"mapId":2,"resourceLocationId":null}""",
                    infoUrl = "https://camping.bcparks.ca/",
                ),
            )
        assertTrue(!out!!.url.contains("resourceLocationId"))
    }

    @Test
    fun `aspira without info_url returns null because we cannot derive a host`() {
        val out =
            cta.computeCta(
                row(providerRefJson = """{"transactionLocationId":1,"mapId":2,"resourceLocationId":null}"""),
            )
        assertNull(out)
    }

    @Test
    fun `non-reservable Forest Service campground uses RIDB official URL`() {
        // POI 441 (Butte Meadows) shape: no provider_ref, info_url points at fs.usda.gov
        val out =
            cta.computeCta(
                row(infoUrl = "https://www.fs.usda.gov/recarea/lassen/recarea/?recid=11276"),
            )
        assertEquals("https://www.fs.usda.gov/recarea/lassen/recarea/?recid=11276", out?.url)
        assertEquals("Park info on fs.usda.gov", out?.label)
        assertEquals("info", out?.kind)
    }

    @Test
    fun `info_url with unrecognized host falls back to bare host label`() {
        val out = cta.computeCta(row(infoUrl = "https://example.org/some/page"))
        assertEquals("Visit example.org", out?.label)
    }

    @Test
    fun `www prefix in host is stripped before label lookup`() {
        val out = cta.computeCta(row(infoUrl = "https://www.nps.gov/yose/index.htm"))
        assertEquals("Park info on nps.gov", out?.label)
    }

    @Test
    fun `no provider_ref and no info_url returns null`() {
        assertNull(cta.computeCta(row()))
    }

    @Test
    fun `blank info_url returns null`() {
        assertNull(cta.computeCta(row(infoUrl = "  ")))
    }

    @Test
    fun `blank reserve_url falls back to info_url`() {
        val out = cta.computeCta(row(reserveUrl = "  ", infoUrl = "https://www.nps.gov/yose/index.htm"))
        assertEquals("Park info on nps.gov", out?.label)
    }

    @Test
    fun `non-provider reserve_url does not override info_url fallback`() {
        val out =
            cta.computeCta(
                row(
                    reserveUrl = "https://reservation.pc.gc.ca/",
                    infoUrl = "https://parks.canada.ca/banff",
                ),
            )
        assertEquals("https://parks.canada.ca/banff", out?.url)
        assertEquals("Park info on parks.canada.ca", out?.label)
    }

    @Test
    fun `provider_ref recgov wins over info_url`() {
        // A reservable rec.gov campground also has its rec.gov page as info_url.
        // We want the canonical "Reserve on recreation.gov" CTA, not the page link.
        val out =
            cta.computeCta(
                row(
                    providerRefJson = """{"recgov_id":"232450"}""",
                    infoUrl = "https://www.recreation.gov/camping/campgrounds/232450",
                ),
            )
        assertEquals("Reserve on recreation.gov", out?.label)
        assertEquals("reserve", out?.kind)
    }

    @Test
    fun `bookingSystem labels`() {
        assertEquals("Recreation.gov", cta.bookingSystem(row(providerRefJson = """{"recgov_id":"232450"}""")))
        assertEquals(
            "Aspira NextGen (Parks Canada)",
            cta.bookingSystem(
                row(
                    providerRefJson = """{"transactionLocationId":1,"mapId":2,"resourceLocationId":null}""",
                    infoUrl = "https://reservation.pc.gc.ca/",
                ),
            ),
        )
        assertEquals(
            "Aspira NextGen (BC Parks)",
            cta.bookingSystem(
                row(
                    providerRefJson = """{"transactionLocationId":1,"mapId":2,"resourceLocationId":null}""",
                    infoUrl = "https://camping.bcparks.ca/",
                ),
            ),
        )
        assertEquals(
            "Aspira NextGen (WA State Parks)",
            cta.bookingSystem(
                row(
                    providerRefJson = """{"transactionLocationId":1,"mapId":2,"resourceLocationId":null}""",
                    infoUrl = "https://washington.goingtocamp.com/",
                ),
            ),
        )
        assertEquals(
            "Aspira NextGen (Parks Canada)",
            cta.bookingSystem(
                row(
                    providerRefJson = """{"transactionLocationId":1,"mapId":2,"resourceLocationId":null}""",
                    reserveUrl = "https://reservation.pc.gc.ca/",
                ),
            ),
        )
        assertNull(cta.bookingSystem(row(infoUrl = "https://www.fs.usda.gov/recarea/")))
        assertNull(cta.bookingSystem(row()))
    }

    private fun row(
        providerRefJson: String? = null,
        ctaProviderRefJson: String? = null,
        reserveUrl: String? = null,
        infoUrl: String? = null,
    ): PoiDetailRow =
        PoiDetailRow(
            id = 441L,
            source = "federal-campgrounds",
            sourceId = "recgov-248965",
            category = "campground",
            subcategory = "federal",
            name = "Butte Meadows Campground",
            region = "CA",
            unitName = null,
            reserveUrl = reserveUrl,
            phone = null,
            infoUrl = infoUrl,
            addressJson = null,
            providerRefJson = providerRefJson,
            geomJson = """{"type":"Point","coordinates":[-121.5,40.0]}""",
            propertiesJson = "{}",
            ctaProviderRefJson = ctaProviderRefJson,
        )
}
