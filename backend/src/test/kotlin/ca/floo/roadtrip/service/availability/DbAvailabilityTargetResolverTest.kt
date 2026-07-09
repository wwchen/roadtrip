package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * DB-backed tests for [DbAvailabilityTargetResolver.resolve], which
 * picks the winning provider_ref among a reservable's linked POIs. Mirrors the
 * DB setup helpers in [ca.floo.roadtrip.service.scheduler.jobs.AvailabilityPollExecutorTest].
 */
class DbAvailabilityTargetResolverTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    /** Seeds a POI. When [campgroundId] is null, provider_ref is left NULL (no
     *  resolvable provider) — otherwise it resolves to ProviderRef.RecGov. */
    private fun seedPoi(campgroundId: String?): Long =
        ctx
            .seedCatalogPoi(
                sourceId = "poi-${campgroundId ?: "none"}-${System.nanoTime()}",
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "test",
                providerRefJson = campgroundId?.let { """{"recgov_id": "$it"}""" },
            ).poiId

    /** Seeds one site under [poiId]'s campground. */
    private fun seedReservable(
        siteId: String,
        poiId: Long,
    ): Long =
        ctx.seedCampsite(
            campgroundId = campgroundIdFor(poiId),
            vendor = "recgov",
            vendorId = siteId,
            name = "Site $siteId",
        )

    private fun campgroundIdFor(poiId: Long): Long =
        ctx
            .fetchOne("SELECT campground_id FROM poi_campgrounds WHERE poi_id = ?", poiId)!!
            .get("campground_id", Long::class.java)

    private class NoopRecgovProvider : ReservationProvider {
        override val id: ReservationProviderId = ReservationProviderId.RECGOV
        override val capabilities: ReservationProviderCapabilities =
            ReservationProviderCapabilities(
                supportsAvailability = true,
                supportsAlerts = true,
                bookingHorizonDays = 180,
                maxPollWindowDays = 60,
            )

        override suspend fun availability(
            ref: ProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }

    private fun resolverFor(reservablesRepo: ReservableRepo): DbAvailabilityTargetResolver =
        DbAvailabilityTargetResolver(
            providerRefs = CampsiteProviderRepo(ctx),
            reservablesRepo = reservablesRepo,
            reservationProviders = ReservationProviderRegistry(mapOf("test" to NoopRecgovProvider())),
            dateResolver = AvailabilityDateResolver(),
        )

    @Test
    fun `resolve carries the parent poi id that supplied the provider ref`() =
        runBlocking {
            val poiA = seedPoi(campgroundId = null)
            val poiB = seedPoi(campgroundId = "232447")
            val reservablesRepo = ReservableRepo(ctx)
            val reservableId = seedReservable("100", poiB)
            val reservable = reservablesRepo.findById(reservableId)!!

            val resolver = resolverFor(reservablesRepo)
            val t = resolver.resolve(reservable)!!

            assertEquals(poiB, t.parentPoiId)
            assertEquals("232447", parentRefKey(t.parentRef))
        }
}
