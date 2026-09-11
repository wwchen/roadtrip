package ca.floo.roadtrip.service.poi.campground

import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CampgroundCtaTest {
    // Fixed clock so the dated Aspira deeplink is byte-stable. 2026-06-17
    // 14:23:45 UTC is 10:23:45 in America/New_York (no DST hop in June).
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-06-17T14:23:45Z"), ZoneId.of("UTC"))
    private val cta = CampgroundCta(clock = fixedClock)

    @Test
    fun `recgov reservable ref labels stored booking URL`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = BookingProviderRef.RecGov(facilityId = "232450"),
                    reserveUrl = "https://www.recreation.gov/camping/campgrounds/232450",
                    infoUrl = null,
                ).singleOrNull()
        assertEquals("https://www.recreation.gov/camping/campgrounds/232450", out?.url)
        assertEquals("Reserve on Recreation.gov", out?.label)
        assertEquals("reserve", out?.kind)
    }

    @Test
    fun `reservecalifornia ref produces park deeplink`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = BookingProviderRef.ReserveCalifornia(placeId = 660, facilityIds = listOf(901)),
                    reserveUrl = null,
                    infoUrl = null,
                ).singleOrNull()
        assertEquals("https://reservecalifornia.com/park/660", out?.url)
        assertEquals("Reserve on ReserveCalifornia", out?.label)
        assertEquals("reserve", out?.kind)
    }

    @Test
    fun `aspira parks canada produces dated NextGen deeplink with tenant label`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = parksCanadaRef,
                    reserveUrl = null,
                    infoUrl = "https://reservation.pc.gc.ca/",
                ).singleOrNull()
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
            cta
                .computeCtas(
                    bookingRef = parksCanadaRef,
                    reserveUrl = "https://reservation.pc.gc.ca/",
                    infoUrl = null,
                ).singleOrNull()
        val url = out?.url
        assertNotNull(url)
        assertTrue(url.startsWith("https://reservation.pc.gc.ca/create-booking/results?"), "host + path: $url")
        assertTrue(url.contains("transactionLocationId=4189"), url)
        assertEquals("Reserve on parks.canada.ca", out.label)
        assertEquals("reserve", out.kind)
    }

    @Test
    fun `aspira deeplink uses the map id carried by the booking ref`() {
        val out =
            cta
                .computeCtas(
                    bookingRef =
                        BookingProviderRef.Aspira(
                            tenant = "pc",
                            transactionLocationId = 4189,
                            mapId = -2147483645,
                            resourceLocationId = 9002,
                        ),
                    reserveUrl = "https://reservation.pc.gc.ca/",
                    infoUrl = null,
                ).singleOrNull()
        assertTrue(out!!.url.contains("mapId=-2147483645"), out.url)
    }

    @Test
    fun `aspira BC parks gets BC Parks label`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = bcParksRef,
                    reserveUrl = null,
                    infoUrl = "https://camping.bcparks.ca/",
                ).singleOrNull()
        assertEquals("Book on BC Parks", out?.label)
        assertTrue(out!!.url.startsWith("https://camping.bcparks.ca/create-booking/results?"))
    }

    @Test
    fun `aspira WA state parks gets WA label`() {
        val out =
            cta
                .computeCtas(
                    bookingRef =
                        BookingProviderRef.Aspira(
                            tenant = "washington",
                            transactionLocationId = 1,
                            mapId = 2,
                            resourceLocationId = null,
                        ),
                    reserveUrl = null,
                    infoUrl = "https://washington.goingtocamp.com/",
                ).singleOrNull()
        assertEquals("Book WA State Park", out?.label)
    }

    @Test
    fun `aspira without resourceLocationId omits it from the URL`() {
        // The string "NULL" or omitting when the tenant requires it bounces
        // WA's results page back to the homepage. We omit cleanly when null.
        val out =
            cta
                .computeCtas(
                    bookingRef = bcParksRef,
                    reserveUrl = null,
                    infoUrl = "https://camping.bcparks.ca/",
                ).singleOrNull()
        assertTrue(!out!!.url.contains("resourceLocationId"))
    }

    @Test
    fun `aspira without info_url returns null because we cannot derive a host`() {
        val out = cta.computeCtas(bookingRef = bcParksRef, reserveUrl = null, infoUrl = null).singleOrNull()
        assertNull(out)
    }

    @Test
    fun `non-reservable Forest Service campground uses RIDB official URL`() {
        // POI 441 (Butte Meadows) shape: no booking ref, info_url points at fs.usda.gov
        val out =
            cta
                .computeCtas(
                    bookingRef = null,
                    reserveUrl = null,
                    infoUrl = "https://www.fs.usda.gov/recarea/lassen/recarea/?recid=11276",
                ).singleOrNull()
        assertEquals("https://www.fs.usda.gov/recarea/lassen/recarea/?recid=11276", out?.url)
        assertEquals("Park info on fs.usda.gov", out?.label)
        assertEquals("info", out?.kind)
    }

    @Test
    fun `info_url with unrecognized host falls back to bare host label`() {
        val out =
            cta.computeCtas(bookingRef = null, reserveUrl = null, infoUrl = "https://example.org/some/page").singleOrNull()
        assertEquals("Visit example.org", out?.label)
    }

    @Test
    fun `www prefix in host is stripped before label lookup`() {
        val out =
            cta.computeCtas(bookingRef = null, reserveUrl = null, infoUrl = "https://www.nps.gov/yose/index.htm").singleOrNull()
        assertEquals("Park info on nps.gov", out?.label)
    }

    @Test
    fun `no booking ref and no info_url returns null`() {
        assertNull(cta.computeCtas(bookingRef = null, reserveUrl = null, infoUrl = null).singleOrNull())
    }

    @Test
    fun `blank info_url returns null`() {
        assertNull(cta.computeCtas(bookingRef = null, reserveUrl = null, infoUrl = "  ").singleOrNull())
    }

    @Test
    fun `blank reserve_url falls back to info_url`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = null,
                    reserveUrl = "  ",
                    infoUrl = "https://www.nps.gov/yose/index.htm",
                ).singleOrNull()
        assertEquals("Park info on nps.gov", out?.label)
    }

    @Test
    fun `non-provider reserve_url does not override info_url fallback`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = null,
                    reserveUrl = "https://reservation.pc.gc.ca/",
                    infoUrl = "https://parks.canada.ca/banff",
                ).singleOrNull()
        assertEquals("https://parks.canada.ca/banff", out?.url)
        assertEquals("Park info on parks.canada.ca", out?.label)
    }

    @Test
    fun `recgov ref wins over info_url`() {
        // A reservable rec.gov campground also has its rec.gov page as info_url.
        // We want the canonical "Reserve on Recreation.gov" CTA, not the page link.
        val out =
            cta
                .computeCtas(
                    bookingRef = BookingProviderRef.RecGov(facilityId = "232450"),
                    reserveUrl = null,
                    infoUrl = "https://www.recreation.gov/camping/campgrounds/232450",
                ).singleOrNull()
        assertEquals("Reserve on Recreation.gov", out?.label)
        assertEquals("reserve", out?.kind)
    }

    @Test
    fun `campflare ref appends public Campflare CTA after primary CTA`() {
        val out =
            cta.computeCtas(
                bookingRef = BookingProviderRef.Campflare(campgroundId = "cranberry-lake-wsp"),
                reserveUrl = null,
                infoUrl = "https://parks.wa.gov/find-parks/state-parks/deception-pass-state-park",
            )

        assertEquals(2, out.size)
        assertEquals("https://parks.wa.gov/find-parks/state-parks/deception-pass-state-park", out[0].url)
        assertEquals("Visit parks.wa.gov", out[0].label)
        assertEquals("info", out[0].kind)
        assertEquals("https://campflare.com/campground/cranberry-lake-wsp", out[1].url)
        assertEquals("View on Campflare", out[1].label)
        assertEquals("info", out[1].kind)
    }

    @Test
    fun `campflare ref with stored reserve_url does not infer recgov CTA`() {
        val out =
            cta.computeCtas(
                bookingRef = BookingProviderRef.Campflare(campgroundId = "white-wolf-campground-567"),
                reserveUrl = "https://www.recreation.gov/camping/campgrounds/10083567",
                infoUrl = "https://www.nps.gov/yose/planyourvisit/wwcamp.htm",
            )

        assertEquals(2, out.size)
        assertEquals("https://www.nps.gov/yose/planyourvisit/wwcamp.htm", out[0].url)
        assertEquals("Park info on nps.gov", out[0].label)
        assertEquals("info", out[0].kind)
        assertEquals("https://campflare.com/campground/white-wolf-campground-567", out[1].url)
        assertEquals("View on Campflare", out[1].label)
    }

    @Test
    fun `campflare ref without primary link returns public Campflare CTA`() {
        val out =
            cta.computeCtas(
                bookingRef = BookingProviderRef.Campflare(campgroundId = "cranberry-lake-wsp"),
                reserveUrl = null,
                infoUrl = null,
            )

        assertEquals(1, out.size)
        assertEquals("https://campflare.com/campground/cranberry-lake-wsp", out.single().url)
        assertEquals("View on Campflare", out.single().label)
    }

    @Test
    fun `a campflare row nobody else claims books through Campflare`() {
        val ref = BookingProviderRef.Campflare(campgroundId = "cranberry-lake-wsp")

        assertEquals(
            "Campflare",
            cta.bookingSystem(bookingRef = ref, reserveUrl = null, infoUrl = "https://parks.wa.gov/find-parks"),
        )
    }

    @Test
    fun `an aliased campflare row served by rec_gov keeps the rec_gov CTA and label`() {
        // The serving provider resolves the pin to its rec.gov alias before the
        // CTA sees it, so the drawer reads exactly as a rec.gov-primary pin.
        val ref = BookingProviderRef.RecGov(facilityId = "234784")

        val out = cta.computeCtas(bookingRef = ref, reserveUrl = null, infoUrl = "https://www.recreation.gov/camping/campgrounds/234784")

        assertEquals(1, out.size)
        assertEquals("Reserve on Recreation.gov", out.single().label)
        assertEquals("reserve", out.single().kind)
        assertEquals(
            "Recreation.gov",
            cta.bookingSystem(bookingRef = ref, reserveUrl = null, infoUrl = "https://www.recreation.gov/camping/campgrounds/234784"),
        )
    }

    @Test
    fun `bookingSystem labels`() {
        assertEquals(
            "Recreation.gov",
            cta.bookingSystem(
                bookingRef = BookingProviderRef.RecGov(facilityId = "232450"),
                reserveUrl = "https://www.recreation.gov/camping/campgrounds/232450",
                infoUrl = null,
            ),
        )
        assertEquals(
            "Aspira NextGen (Parks Canada)",
            cta.bookingSystem(bookingRef = bcParksRef, reserveUrl = null, infoUrl = "https://reservation.pc.gc.ca/"),
        )
        assertEquals(
            "Aspira NextGen (BC Parks)",
            cta.bookingSystem(bookingRef = bcParksRef, reserveUrl = null, infoUrl = "https://camping.bcparks.ca/"),
        )
        assertEquals(
            "Aspira NextGen (WA State Parks)",
            cta.bookingSystem(bookingRef = bcParksRef, reserveUrl = null, infoUrl = "https://washington.goingtocamp.com/"),
        )
        assertEquals(
            "Aspira NextGen (Parks Canada)",
            cta.bookingSystem(bookingRef = bcParksRef, reserveUrl = "https://reservation.pc.gc.ca/", infoUrl = null),
        )
        assertNull(cta.bookingSystem(bookingRef = null, reserveUrl = null, infoUrl = "https://www.fs.usda.gov/recarea/"))
        assertNull(cta.bookingSystem(bookingRef = null, reserveUrl = null, infoUrl = null))
    }

    private companion object {
        val parksCanadaRef =
            BookingProviderRef.Aspira(
                tenant = "pc",
                transactionLocationId = 4189,
                mapId = -2147483361,
                resourceLocationId = -2147483408,
            )

        val bcParksRef =
            BookingProviderRef.Aspira(
                tenant = "bcparks",
                transactionLocationId = 1,
                mapId = 2,
                resourceLocationId = null,
            )
    }
}
