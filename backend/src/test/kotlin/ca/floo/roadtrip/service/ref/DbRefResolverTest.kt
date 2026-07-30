package ca.floo.roadtrip.service.ref

import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.repo.RefLinkRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampground
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DbRefResolverTest : SharedDbTest() {
    private lateinit var resolver: DbRefResolver

    @BeforeEach
    fun setup() {
        ctx.cleanCanonicalCatalogFixtures()
        resolver = DbRefResolver(RefLinkRepo(ctx))
    }

    @Test
    fun `poiId resolves to campground booking ref`() {
        val poiId =
            ctx
                .seedCatalogPoi(
                    sourceId = "facility-232447",
                    name = "Upper Pines",
                    lon = -119.56,
                    lat = 37.74,
                    source = "recgov",
                    bookingProvider = "recgov",
                    bookingProviderRef = "232447",
                ).poiId

        val result = resolver.resolve<RefValue.CampgroundBookingRef>(RefValue.PoiId(poiId))

        assertEquals(1, result.size)
        assertEquals(BookingProviderRef.RecGov(facilityId = "232447"), result[0].ref)
    }

    @Test
    fun `campsiteId resolves to its own campsite booking ref`() {
        val poi =
            ctx.seedCatalogPoi(
                sourceId = "facility-232447",
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "recgov",
                bookingProvider = "recgov",
                bookingProviderRef = "232447",
            )
        val campgroundId =
            ctx
                .fetchOne(
                    "SELECT campground_id FROM poi_campgrounds WHERE poi_id = ?",
                    poi.poiId,
                )!!
                .get("campground_id", Long::class.java)
        val campsiteId =
            ctx.seedCampsite(
                campgroundId = campgroundId,
                vendor = "recgov",
                vendorId = "site-1",
                bookingProvider = "recgov",
                bookingProviderRef = "site-1",
            )

        val result = resolver.resolve<RefValue.CampsiteBookingRef>(RefValue.CampsiteId(campsiteId))

        assertEquals(1, result.size)
        assertEquals(BookingProviderRef.RecGov(facilityId = "site-1"), result[0].ref)
    }

    @Test
    fun `campsiteId resolves to parent campground booking ref`() {
        val poi =
            ctx.seedCatalogPoi(
                sourceId = "facility-232447",
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "recgov",
                bookingProvider = "recgov",
                bookingProviderRef = "232447",
            )
        val campgroundId =
            ctx
                .fetchOne(
                    "SELECT campground_id FROM poi_campgrounds WHERE poi_id = ?",
                    poi.poiId,
                )!!
                .get("campground_id", Long::class.java)
        val campsiteId =
            ctx.seedCampsite(
                campgroundId = campgroundId,
                vendor = "recgov",
                vendorId = "site-1",
                bookingProvider = "recgov",
                bookingProviderRef = "site-1",
            )

        val result = resolver.resolve<RefValue.CampgroundBookingRef>(RefValue.CampsiteId(campsiteId))

        assertEquals(1, result.size)
        assertEquals(BookingProviderRef.RecGov(facilityId = "232447"), result[0].ref)
    }

    @Test
    fun `campsiteId resolves to poiId`() {
        val poi =
            ctx.seedCatalogPoi(
                sourceId = "facility-232447",
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "recgov",
                bookingProvider = "recgov",
                bookingProviderRef = "232447",
            )
        val campgroundId =
            ctx
                .fetchOne(
                    "SELECT campground_id FROM poi_campgrounds WHERE poi_id = ?",
                    poi.poiId,
                )!!
                .get("campground_id", Long::class.java)
        val campsiteId =
            ctx.seedCampsite(
                campgroundId = campgroundId,
                vendor = "recgov",
                vendorId = "site-1",
                bookingProvider = "recgov",
                bookingProviderRef = "site-1",
            )

        val result = resolver.resolve<RefValue.PoiId>(RefValue.CampsiteId(campsiteId))

        assertEquals(listOf(RefValue.PoiId(poi.poiId)), result)
    }

    @Test
    fun `campgroundBookingRef resolves to campgroundId`() {
        val campgroundId =
            ctx.seedCampground(
                source = "recgov",
                sourceId = "232447",
                bookingProvider = "recgov",
                bookingProviderRef = "232447",
            )

        val result =
            resolver.resolve<RefValue.CampgroundId>(
                RefValue.CampgroundBookingRef(BookingProviderRef.RecGov(facilityId = "232447")),
            )

        assertEquals(listOf(RefValue.CampgroundId(campgroundId)), result)
    }

    @Test
    fun `campgroundBookingRef resolves to child campsiteIds`() {
        val campgroundId =
            ctx.seedCampground(
                source = "recgov",
                sourceId = "232447",
                bookingProvider = "recgov",
                bookingProviderRef = "232447",
            )
        val cs1 = ctx.seedCampsite(campgroundId = campgroundId, vendor = "recgov", vendorId = "s1")
        val cs2 = ctx.seedCampsite(campgroundId = campgroundId, vendor = "recgov", vendorId = "s2")

        val result =
            resolver.resolve<RefValue.CampsiteId>(
                RefValue.CampgroundBookingRef(BookingProviderRef.RecGov(facilityId = "232447")),
            )

        assertEquals(setOf(cs1, cs2), result.map { it.id }.toSet())
    }

    @Test
    fun `batch resolve returns grouped results`() {
        val poi1 =
            ctx.seedCatalogPoi(
                sourceId = "fac-1",
                name = "A",
                lon = -119.0,
                lat = 37.0,
                source = "recgov",
                bookingProvider = "recgov",
                bookingProviderRef = "1",
            )
        val poi2 =
            ctx.seedCatalogPoi(
                sourceId = "fac-2",
                name = "B",
                lon = -120.0,
                lat = 38.0,
                source = "recgov",
                bookingProvider = "recgov",
                bookingProviderRef = "2",
            )

        val inputs = listOf(RefValue.PoiId(poi1.poiId), RefValue.PoiId(poi2.poiId))
        val result = resolver.resolve<RefValue.CampgroundBookingRef>(inputs)

        assertEquals(2, result.size)
        assertEquals(
            BookingProviderRef.RecGov("1"),
            result[RefValue.PoiId(poi1.poiId)]!!.first().ref,
        )
        assertEquals(
            BookingProviderRef.RecGov("2"),
            result[RefValue.PoiId(poi2.poiId)]!!.first().ref,
        )
    }

    @Test
    fun `unsupported resolution path returns empty list`() {
        val result =
            resolver.resolve<RefValue.CampsiteDataRef>(
                RefValue.CampgroundBookingRef(BookingProviderRef.RecGov("232447")),
            )
        assertEquals(emptyList(), result)
    }
}
