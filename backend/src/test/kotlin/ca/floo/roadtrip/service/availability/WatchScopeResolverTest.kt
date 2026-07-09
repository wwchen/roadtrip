package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class WatchScopeResolverTest : SharedDbTest() {
    private lateinit var reservableRepo: ReservableRepo
    private lateinit var watchRepo: AvailabilityWatchRepo
    private lateinit var resolver: WatchScopeResolver

    @BeforeEach
    fun setUp() {
        reservableRepo = ReservableRepo(ctx)
        watchRepo = AvailabilityWatchRepo(ctx)
        resolver = WatchScopeResolver(reservableRepo)
        ctx.cleanCanonicalCatalogFixtures()
    }

    private var poiSeq = 0

    private fun insertPoi(): Long {
        val sourceId = "poi-scope-${poiSeq++}"
        return ctx
            .seedCatalogPoi(
                sourceId = sourceId,
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "test",
            ).poiId
    }

    private fun insertReservable(
        poiId: Long,
        vendorId: String,
    ): Long =
        ctx.seedCampsite(
            campgroundId = campgroundIdFor(poiId),
            vendor = "test",
            vendorId = vendorId,
            name = "Site $vendorId",
        )

    private fun campgroundIdFor(poiId: Long): Long =
        ctx
            .fetchOne("SELECT campground_id FROM poi_campgrounds WHERE poi_id = ?", poiId)!!
            .get("campground_id", Long::class.java)

    private fun createWatch(targets: List<AvailabilityWatchTargetRepo.TargetInput>): AvailabilityWatchRepo.Watch =
        watchRepo.create(
            AvailabilityWatchRepo.CreateInput(
                targets = targets,
                reservableFilters = JsonObject(emptyMap()),
                startDate = LocalDate.parse("2026-07-04"),
                endDate = LocalDate.parse("2026-07-06"),
                cadenceSec = 60,
                triggerKinds = listOf("atc"),
                triggerConfig = JsonObject(emptyMap()),
                stopWhenTriggered = false,
            ),
        )

    @Test
    fun `resolve unions reservables across a poi target and a reservable target`() {
        val poiA = insertPoi()
        val poiB = insertPoi()
        val reservableInA1 = insertReservable(poiA, "a1")
        val reservableInA2 = insertReservable(poiA, "a2")
        val reservableInB = insertReservable(poiB, "b1")

        val watch =
            createWatch(
                listOf(
                    AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, reservableId = null),
                    AvailabilityWatchTargetRepo.TargetInput(poiId = null, reservableId = reservableInB),
                ),
            )

        val resolved = resolver.resolve(watch).map { it.id }.toSet()

        assertEquals(setOf(reservableInA1, reservableInA2, reservableInB), resolved)
    }

    @Test
    fun `resolve de-duplicates a reservable reachable via two targets`() {
        val poi = insertPoi()
        val reservable = insertReservable(poi, "dup")

        val watch =
            createWatch(
                listOf(
                    AvailabilityWatchTargetRepo.TargetInput(poiId = poi, reservableId = null),
                    AvailabilityWatchTargetRepo.TargetInput(poiId = null, reservableId = reservable),
                ),
            )

        val resolved = resolver.resolve(watch)

        assertEquals(1, resolved.size)
        assertEquals(reservable, resolved.single().id)
    }
}
