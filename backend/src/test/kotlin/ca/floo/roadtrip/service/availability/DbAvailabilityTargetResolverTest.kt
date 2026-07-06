package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.SharedDbTest
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
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
    }

    /** Seeds a POI. When [campgroundId] is null, provider_ref is left NULL (no
     *  resolvable provider) — otherwise it resolves to ProviderRef.RecGov. */
    private fun seedPoi(campgroundId: String?): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO pois (
                    source, source_id, category, name, geom, region,
                    properties, provider_ref, fetched_at
                ) VALUES (
                    'test', ?, 'campground', 'Upper Pines',
                    ST_SetSRID(ST_MakePoint(-119.56, 37.74), 4326),
                    'CA', '{}'::jsonb, ?::jsonb, '2026-06-01 00:00:00+00'::timestamptz
                ) RETURNING id
                """.trimIndent(),
                "poi-${campgroundId ?: "none"}-${System.nanoTime()}",
                campgroundId?.let { """{"recgov_id": "$it"}""" },
            )!!
            .get("id", Long::class.java)

    /** Seeds one reservable (site) and links it to every poi id given. */
    private fun seedReservable(
        siteId: String,
        poiIds: List<Long>,
    ): Long {
        val reservableId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO reservables (type, vendor, vendor_id, name, source)
                    VALUES ('site', 'recgov', ?, ?, 'test')
                    RETURNING id
                    """.trimIndent(),
                    siteId,
                    "Site $siteId",
                )!!
                .get("id", Long::class.java)
        poiIds.forEach { poiId ->
            ctx.execute(
                "INSERT INTO reservable_pois (reservable_id, poi_id) VALUES (?, ?)",
                reservableId,
                poiId,
            )
        }
        return reservableId
    }

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
            val reservableId = seedReservable("100", listOf(poiA, poiB))
            val reservable = reservablesRepo.findById(reservableId)!!

            val resolver = resolverFor(reservablesRepo)
            val t = resolver.resolve(reservable)!!

            assertEquals(poiB, t.parentPoiId)
            assertEquals("232447", parentRefKey(t.parentRef))
        }
}
