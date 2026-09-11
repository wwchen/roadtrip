package ca.floo.roadtrip.service.poi.campground

import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun `aspira deeplink is dated from the POI's own date context`() {
        val out =
            cta
                .computeCtas(
                    bookingRef = bcParksRef,
                    reserveUrl = null,
                    infoUrl = null,
                    dateContext = pacificContext,
                ).single()
        assertNotNull(out.url)
        assertTrue(out.url.startsWith("https://camping.bcparks.ca/create-booking/results?"), out.url)
        assertTrue(out.url.contains("startDate=2026-06-17"), out.url)
        assertTrue(out.url.contains("endDate=2026-06-18"), out.url)
        assertEquals("Reserve on BC Parks", out.label)
        assertEquals("reserve", out.kind)
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
        assertEquals("View on Campflare", out.label)
        assertEquals("info", out.kind)
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
