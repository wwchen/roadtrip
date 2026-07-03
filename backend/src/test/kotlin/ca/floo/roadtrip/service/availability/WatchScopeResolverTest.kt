package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.models.domain.ReservableType
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.SharedDbTest
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
        ctx.execute("DELETE FROM availability_watch_target")
        ctx.execute("DELETE FROM availability_watch")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
    }

    private var poiSeq = 0

    private fun insertPoi(): Long {
        val sourceId = "poi-scope-${poiSeq++}"
        return ctx
            .fetchOne(
                """
                INSERT INTO pois (
                    source, source_id, category, name, geom, region,
                    properties, provider_ref, fetched_at
                ) VALUES (
                    'test', ?, 'campground', 'Upper Pines',
                    ST_SetSRID(ST_MakePoint(-119.56, 37.74), 4326),
                    'CA', '{}'::jsonb, NULL, '2026-06-01 00:00:00+00'::timestamptz
                ) RETURNING id
                """.trimIndent(),
                sourceId,
            )!!
            .get("id", Long::class.java)
    }

    private fun insertReservable(
        poiId: Long,
        vendorId: String,
    ): Long {
        val id =
            reservableRepo.upsert(
                ReservableRepo.Input(
                    rid = ReservableId(type = ReservableType.SITE, vendor = "test", vendorId = vendorId),
                    name = "Site $vendorId",
                    loop = null,
                    siteType = null,
                    raw = null,
                ),
            )
        reservableRepo.linkToPoi(id, poiId)
        return id
    }

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
