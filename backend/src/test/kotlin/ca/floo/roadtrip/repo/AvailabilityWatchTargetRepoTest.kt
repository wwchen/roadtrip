package ca.floo.roadtrip.repo

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvailabilityWatchTargetRepoTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability_watch_poller")
        ctx.execute("DELETE FROM availability_poller")
        ctx.execute("DELETE FROM availability_watch_target")
        ctx.execute("DELETE FROM availability_watch")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
    }

    private var poiSeq = 0

    private fun insertPoi(): Long {
        val sourceId = "poi-target-repo-${poiSeq++}"
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

    private fun insertReservable(vendorId: String): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO reservables (type, vendor, vendor_id, name, source)
                VALUES ('site', 'test', ?, ?, 'test')
                RETURNING id
                """.trimIndent(),
                vendorId,
                "Site $vendorId",
            )!!
            .get("id", Long::class.java)

    private fun watchExists(watchId: Long): Boolean =
        ctx
            .fetchOne("SELECT count(*) AS c FROM availability_watch WHERE id = ?", watchId)!!
            .get("c", Long::class.java) > 0

    private fun insertWatch(): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO availability_watch (start_date, end_date, cadence_sec, trigger_kinds)
                VALUES ('2026-07-04'::date, '2026-07-06'::date, 60, ARRAY['atc'])
                RETURNING id
                """.trimIndent(),
            )!!
            .get("id", Long::class.java)

    @Test
    fun `replaceForWatch inserts a mixed set of poi and reservable targets`() {
        val watchId = insertWatch()
        val poiId = insertPoi()
        val reservableId = insertReservable("site-a")
        val repo = AvailabilityWatchTargetRepo(ctx)

        repo.replaceForWatch(
            watchId,
            listOf(
                AvailabilityWatchTargetRepo.TargetInput(poiId = poiId, reservableId = null),
                AvailabilityWatchTargetRepo.TargetInput(poiId = null, reservableId = reservableId),
            ),
        )

        val targets = repo.listForWatch(watchId)
        assertEquals(2, targets.size)
        assertTrue(targets.any { it.poiId == poiId })
        assertTrue(targets.any { it.reservableId == reservableId })
    }

    @Test
    fun `replaceForWatch on an existing set drops stale targets`() {
        val watchId = insertWatch()
        val poiA = insertPoi()
        val poiB = insertPoi()
        val repo = AvailabilityWatchTargetRepo(ctx)

        repo.replaceForWatch(watchId, listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, reservableId = null)))
        repo.replaceForWatch(watchId, listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, reservableId = null)))

        val targets = repo.listForWatch(watchId)
        assertEquals(1, targets.size)
        assertEquals(poiB, targets.single().poiId)
    }

    @Test
    fun `deleteForWatch removes all targets for that watch only`() {
        val watchA = insertWatch()
        val watchB = insertWatch()
        val poi = insertPoi()
        val repo = AvailabilityWatchTargetRepo(ctx)
        repo.replaceForWatch(watchA, listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poi, reservableId = null)))
        repo.replaceForWatch(watchB, listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poi, reservableId = null)))

        val deleted = repo.deleteForWatch(watchA)

        assertEquals(1, deleted)
        assertTrue(repo.listForWatch(watchA).isEmpty())
        assertEquals(1, repo.listForWatch(watchB).size)
    }

    @Test
    fun `deleting the last target's reservable prunes the now-empty watch and its poller link`() {
        val watchId = insertWatch()
        val reservableId = insertReservable("site-last-target")
        val repo = AvailabilityWatchTargetRepo(ctx)
        repo.replaceForWatch(watchId, listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = null, reservableId = reservableId)))
        val poi = insertPoi()
        val pollerRepo = AvailabilityPollerRepo(ctx)
        val pollerId = pollerRepo.upsertActive("test", "parent-last-target", poi, pullNextRunAt = null)
        pollerRepo.linkWatch(watchId, pollerId)

        ctx.execute("DELETE FROM reservables WHERE id = ?", reservableId)

        assertTrue(repo.listForWatch(watchId).isEmpty())
        assertTrue(!watchExists(watchId))
        assertTrue(pollerRepo.pollerIdsForWatch(watchId).isEmpty())
    }

    @Test
    fun `losing one of several targets leaves the watch and remaining targets intact`() {
        val watchId = insertWatch()
        val poiA = insertPoi()
        val poiB = insertPoi()
        val repo = AvailabilityWatchTargetRepo(ctx)
        repo.replaceForWatch(
            watchId,
            listOf(
                AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, reservableId = null),
                AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, reservableId = null),
            ),
        )

        ctx.execute("DELETE FROM pois WHERE id = ?", poiA)

        val remaining = repo.listForWatch(watchId)
        assertEquals(1, remaining.size)
        assertEquals(poiB, remaining.single().poiId)
        assertTrue(watchExists(watchId))
    }
}
