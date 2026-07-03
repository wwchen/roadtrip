package ca.floo.roadtrip.repo

import ca.floo.roadtrip.service.availability.WatchStatus
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvailabilityWatchRepoTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability_watch_target")
        ctx.execute("DELETE FROM availability_watch")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
    }

    private var poiSeq = 0

    private fun insertPoi(): Long {
        val sourceId = "poi-repo-${poiSeq++}"
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

    private fun createInput(targets: List<AvailabilityWatchTargetRepo.TargetInput>): AvailabilityWatchRepo.CreateInput =
        AvailabilityWatchRepo.CreateInput(
            targets = targets,
            reservableFilters = JsonObject(emptyMap()),
            startDate = LocalDate.parse("2026-07-04"),
            endDate = LocalDate.parse("2026-07-06"),
            cadenceSec = 60,
            triggerKinds = listOf("atc"),
            triggerConfig = JsonObject(emptyMap()),
            stopWhenTriggered = false,
        )

    @Test
    fun `create persists a multi-poi target set`() {
        val poiA = insertPoi()
        val poiB = insertPoi()
        val repo = AvailabilityWatchRepo(ctx)

        val watch =
            repo.create(
                createInput(
                    listOf(
                        AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, reservableId = null),
                        AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, reservableId = null),
                    ),
                ),
            )

        assertEquals(2, watch.targets.size)
        assertEquals(setOf(poiA, poiB), watch.targets.mapNotNull { it.poiId }.toSet())
    }

    @Test
    fun `findById reloads the persisted target set`() {
        val poi = insertPoi()
        val repo = AvailabilityWatchRepo(ctx)
        val created = repo.create(createInput(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poi, reservableId = null))))

        val reloaded = repo.findById(created.id)!!

        assertEquals(1, reloaded.targets.size)
        assertEquals(poi, reloaded.targets.single().poiId)
    }

    @Test
    fun `update replaces the target set when targets is provided`() {
        val poiA = insertPoi()
        val poiB = insertPoi()
        val repo = AvailabilityWatchRepo(ctx)
        val created = repo.create(createInput(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, reservableId = null))))

        val updated =
            repo.update(
                created.id,
                AvailabilityWatchRepo.UpdateInput(
                    targets = listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, reservableId = null)),
                ),
            )!!

        assertEquals(1, updated.targets.size)
        assertEquals(poiB, updated.targets.single().poiId)
    }

    @Test
    fun `update without targets leaves the existing target set untouched`() {
        val poi = insertPoi()
        val repo = AvailabilityWatchRepo(ctx)
        val created = repo.create(createInput(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poi, reservableId = null))))

        val updated = repo.update(created.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.PAUSED))!!

        assertEquals(1, updated.targets.size)
        assertEquals(poi, updated.targets.single().poiId)
        assertEquals(WatchStatus.PAUSED, updated.status)
    }

    @Test
    fun `list filtered by poiId matches watches whose target set includes that poi`() {
        val poiA = insertPoi()
        val poiB = insertPoi()
        val repo = AvailabilityWatchRepo(ctx)
        val watchWithA =
            repo.create(
                createInput(
                    listOf(
                        AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, reservableId = null),
                        AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, reservableId = null),
                    ),
                ),
            )
        val watchWithBOnly = repo.create(createInput(listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, reservableId = null))))

        val filtered = repo.list(poiId = poiA)

        assertEquals(listOf(watchWithA.id), filtered.map { it.id })
        assertTrue(repo.list(poiId = poiB).map { it.id }.toSet() == setOf(watchWithA.id, watchWithBOnly.id))
    }
}
