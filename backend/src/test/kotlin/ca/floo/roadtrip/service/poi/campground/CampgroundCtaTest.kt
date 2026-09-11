package ca.floo.roadtrip.service.poi.campground

import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CampgroundCtaTest {
    private val cta = CampgroundCta(shippedTenantRegistry())

    // A day where Pacific and Eastern disagree: 2026-06-17 in America/Vancouver
    // while America/New_York has already rolled to the 18th. The old EST anchor
    // dated every tenant's deeplink off the wrong day for exactly this case.
    private val pacificContext =
        PoiDateContext(timeZone = ZoneId.of("America/Vancouver"), earliestDate = LocalDate.parse("2026-06-17"))

    @Test
    fun `bookingSystem names the tenant, not the vendor`() {
        assertEquals("Parks Canada", cta.bookingSystem(parksCanadaRef))
        assertEquals("BC Parks", cta.bookingSystem(bcParksRef))
        assertEquals("Washington State Parks", cta.bookingSystem(washingtonRef))
        assertEquals("Recreation.gov", cta.bookingSystem(BookingProviderRef.RecGov(facilityId = "232450")))
        assertEquals("Campflare", cta.bookingSystem(BookingProviderRef.Campflare(campgroundId = "9")))
        assertEquals("Alberta Parks", cta.bookingSystem(BookingProviderRef.ReserveAmerica("ABPP", "10")))
        assertEquals("New York State Parks", cta.bookingSystem(BookingProviderRef.ReserveAmerica("NY", "10")))
        assertEquals(
            "ReserveCalifornia",
            cta.bookingSystem(BookingProviderRef.ReserveCalifornia(placeId = 660, facilityIds = listOf(901))),
        )
        assertEquals("Aspira NextGen", cta.bookingSystem(unknownTenantRef))
        assertNull(cta.bookingSystem(null))
    }

    @Test
    fun `recgov keeps a stored recreation_gov URL`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = BookingProviderRef.RecGov(facilityId = "232450"),
                    reserveUrl = "https://www.recreation.gov/camping/campgrounds/232450",
                    infoUrl = null,
                    dateContext = pacificContext,
                ).single()
        assertEquals("https://www.recreation.gov/camping/campgrounds/232450", out.url)
        assertEquals("Reserve on Recreation.gov", out.label)
        assertEquals("reserve", out.kind)
    }

    @Test
    fun `recgov rebuilds the URL when the stored one is a foreign host`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = BookingProviderRef.RecGov(facilityId = "232450"),
                    reserveUrl = "https://campflare.com/campgrounds/232450",
                    infoUrl = null,
                    dateContext = pacificContext,
                ).first()
        assertEquals("https://www.recreation.gov/camping/campgrounds/232450", out.url)
        assertEquals("Reserve on Recreation.gov", out.label)
    }

    @Test
    fun `recgov ref wins over a recreation_gov info link`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = BookingProviderRef.RecGov(facilityId = "232450"),
                    reserveUrl = null,
                    infoUrl = "https://www.recreation.gov/camping/campgrounds/232450",
                    dateContext = pacificContext,
                ).single()
        assertEquals("Reserve on Recreation.gov", out.label)
        assertEquals("reserve", out.kind)
    }

    @Test
    fun `aspira deeplink is dated from the POI's own date context`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = bcParksRef,
                    reserveUrl = null,
                    infoUrl = null,
                    dateContext = pacificContext,
                ).single()
        assertTrue(out.url.startsWith("https://camping.bcparks.ca/create-booking/results?"), out.url)
        assertTrue(out.url.contains("startDate=2026-06-17"), out.url)
        assertTrue(out.url.contains("endDate=2026-06-18"), out.url)
        // A NULL or omitted resourceLocationId bounces WA's results page back
        // to the homepage, so the param is left out rather than sent empty.
        assertTrue(!out.url.contains("resourceLocationId"), out.url)
        assertEquals("Reserve on BC Parks", out.label)
        assertEquals("reserve", out.kind)
    }

    @Test
    fun `an aspira tenant the registry does not name gets no reserve CTA`() {
        val out =
            cta.computeCtas(
                bookingRef = unknownTenantRef,
                reserveUrl = "https://reservation.pc.gc.ca/",
                infoUrl = null,
                dateContext = pacificContext,
            )
        assertTrue(out.isEmpty(), out.toString())
    }

    @Test
    fun `campflare ref gets the vendor's view link`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = BookingProviderRef.Campflare(campgroundId = "9"),
                    reserveUrl = null,
                    infoUrl = null,
                    dateContext = pacificContext,
                ).single()
        assertEquals("https://campflare.com/campground/9", out.url)
        assertEquals("View on Campflare", out.label)
        assertEquals("info", out.kind)
    }

    @Test
    fun `campflare ref appends the public Campflare CTA after the primary CTA`() {
        val out =
            cta.computeCtas(
                bookingRef = BookingProviderRef.Campflare(campgroundId = "cranberry-lake-wsp"),
                reserveUrl = null,
                infoUrl = "https://parks.wa.gov/find-parks/state-parks/deception-pass-state-park",
                dateContext = pacificContext,
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
    fun `campflare ref with a stored reserve_url does not infer a recgov CTA`() {
        val out =
            cta.computeCtas(
                bookingRef = BookingProviderRef.Campflare(campgroundId = "white-wolf-campground-567"),
                reserveUrl = "https://www.recreation.gov/camping/campgrounds/10083567",
                infoUrl = "https://www.nps.gov/yose/planyourvisit/wwcamp.htm",
                dateContext = pacificContext,
            )
        assertEquals(2, out.size)
        assertEquals("https://www.nps.gov/yose/planyourvisit/wwcamp.htm", out[0].url)
        assertEquals("Park info on nps.gov", out[0].label)
        assertEquals("info", out[0].kind)
        assertEquals("https://campflare.com/campground/white-wolf-campground-567", out[1].url)
        assertEquals("View on Campflare", out[1].label)
    }

    @Test
    fun `an info link that is the campflare page itself is not repeated`() {
        val out =
            cta.computeCtas(
                bookingRef = BookingProviderRef.Campflare(campgroundId = "cranberry-lake-wsp"),
                reserveUrl = null,
                infoUrl = "https://campflare.com/campground/cranberry-lake-wsp",
                dateContext = pacificContext,
            )
        assertEquals(1, out.size)
        assertEquals("https://campflare.com/campground/cranberry-lake-wsp", out.single().url)
        assertEquals("View on Campflare", out.single().label)
    }

    @Test
    fun `an aliased campflare identity keeps its vendor link behind the selling CTA`() {
        val out =
            cta.computeCtas(
                bookingRef = BookingProviderRef.RecGov(facilityId = "234784"),
                reserveUrl = null,
                infoUrl = null,
                dateContext = pacificContext,
                identities = listOf(BookingProviderRef.Campflare(campgroundId = "icicle-group-campground-8149")),
            )
        assertEquals(2, out.size)
        assertEquals("Reserve on Recreation.gov", out[0].label)
        assertEquals("reserve", out[0].kind)
        assertEquals("https://campflare.com/campground/icicle-group-campground-8149", out[1].url)
        assertEquals("View on Campflare", out[1].label)
        assertEquals("info", out[1].kind)
    }

    @Test
    fun `reservecalifornia ref produces the park deeplink`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = BookingProviderRef.ReserveCalifornia(placeId = 660, facilityIds = listOf(901)),
                    reserveUrl = null,
                    infoUrl = null,
                    dateContext = pacificContext,
                ).single()
        assertEquals("https://reservecalifornia.com/park/660", out.url)
        assertEquals("Reserve on ReserveCalifornia", out.label)
    }

    @Test
    fun `an agency info URL keeps its agency label`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = null,
                    reserveUrl = null,
                    infoUrl = "https://www.fs.usda.gov/recarea/1",
                    dateContext = pacificContext,
                ).single()
        assertEquals("Park info on fs.usda.gov", out.label)
        assertEquals("info", out.kind)
    }

    @Test
    fun `a vendor info URL on a pin nobody books reads as a view link`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = null,
                    reserveUrl = null,
                    infoUrl = "https://www.recreation.gov/camping/campgrounds/232450",
                    dateContext = pacificContext,
                ).single()
        assertEquals("View on Recreation.gov", out.label)
        assertEquals("info", out.kind)
    }

    @Test
    fun `no booking ref and no info_url yields no CTAs`() {
        val out = cta.computeCtas(bookingRef = null, reserveUrl = null, infoUrl = null, dateContext = pacificContext)
        assertTrue(out.isEmpty(), out.toString())
    }

    @Test
    fun `a blank info_url yields no CTAs`() {
        val out = cta.computeCtas(bookingRef = null, reserveUrl = null, infoUrl = "  ", dateContext = pacificContext)
        assertTrue(out.isEmpty(), out.toString())
    }

    @Test
    fun `a blank reserve_url falls back to info_url`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = null,
                    reserveUrl = "  ",
                    infoUrl = "https://www.nps.gov/yose/index.htm",
                    dateContext = pacificContext,
                ).single()
        assertEquals("https://www.nps.gov/yose/index.htm", out.url)
        assertEquals("Park info on nps.gov", out.label)
    }

    @Test
    fun `a non-provider reserve_url does not override the info CTA's URL`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = null,
                    reserveUrl = "https://reservation.pc.gc.ca/",
                    infoUrl = "https://parks.canada.ca/banff",
                    dateContext = pacificContext,
                ).single()
        assertEquals("https://parks.canada.ca/banff", out.url)
        assertEquals("Park info on parks.canada.ca", out.label)
    }

    private companion object {
        val parksCanadaRef =
            BookingProviderRef.Aspira(tenant = "pc", transactionLocationId = 4189, mapId = -2147483361, resourceLocationId = null)
        val bcParksRef =
            BookingProviderRef.Aspira(tenant = "bc", transactionLocationId = 1, mapId = -2147483470, resourceLocationId = null)
        val washingtonRef =
            BookingProviderRef.Aspira(tenant = "wa", transactionLocationId = 2, mapId = -2147483600, resourceLocationId = null)
        val unknownTenantRef =
            BookingProviderRef.Aspira(tenant = "zz", transactionLocationId = 3, mapId = -2147483601, resourceLocationId = null)
    }
}
