package ca.floo.roadtrip.repo

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvailabilityWatchTargetRepoTest : SharedDbTest() {
    private var poiSeq = 0
    private var userSeq = 0

    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    private fun seedOwner(): Long = UserRepo(ctx).create(
        email = "owner-${userSeq++}@example.com",
        displayName = null,
        isEmailVerified = true,
    ).id.value

    private fun insertPoi(): Long {
        val sourceId = "poi-target-repo-${poiSeq++}"
        return ctx.seedCatalogPoi(sourceId = sourceId, name = "Upper Pines", lon = -119.56, lat = 37.74).poiId
    }

    private fun insertCampsite(vendorId: String): Long =
        ctx.seedCampsite(
            campgroundId = ctx.seedCampground(source = "recgov", sourceId = "cg-$vendorId"),
            vendor = "recgov",
            vendorId = vendorId,
            name = "Site $vendorId",
        )

    private fun watchExists(watchId: Long): Boolean =
        ctx
            .fetchOne("SELECT count(*) AS c FROM availability_watch WHERE id = ?", watchId)!!
            .get("c", Long::class.java) > 0

    private fun insertWatch(): Long {
        val ownerId = seedOwner()
        return ctx
            .fetchOne(
                """
                INSERT INTO availability_watch (owner_user_id, start_date, end_date, cadence_sec, trigger_kinds)
                VALUES (?, '2026-07-04'::date, '2026-07-06'::date, 60, ARRAY['atc'])
                RETURNING id
                """.trimIndent(),
                ownerId,
            )!!
            .get("id", Long::class.java)
    }

    @Test
    fun `replaceForWatch inserts a mixed set of poi and reservable targets`() {
        val watchId = insertWatch()
        val poiId = insertPoi()
        val campsiteId = insertCampsite("site-a")
        val repo = AvailabilityWatchTargetRepo(ctx)

        repo.replaceForWatch(
            watchId,
            listOf(
                AvailabilityWatchTargetRepo.TargetInput(poiId = poiId, campsiteId = null),
                AvailabilityWatchTargetRepo.TargetInput(poiId = null, campsiteId = campsiteId),
            ),
        )

        val targets = repo.listForWatch(watchId)
        assertEquals(2, targets.size)
        assertTrue(targets.any { it.poiId == poiId })
        assertTrue(targets.any { it.campsiteId == campsiteId })
    }

    @Test
    fun `replaceForWatch on an existing set drops stale targets`() {
        val watchId = insertWatch()
        val poiA = insertPoi()
        val poiB = insertPoi()
        val repo = AvailabilityWatchTargetRepo(ctx)

        repo.replaceForWatch(watchId, listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, campsiteId = null)))
        repo.replaceForWatch(watchId, listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, campsiteId = null)))

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
        repo.replaceForWatch(watchA, listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poi, campsiteId = null)))
        repo.replaceForWatch(watchB, listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poi, campsiteId = null)))

        val deleted = repo.deleteForWatch(watchA)

        assertEquals(1, deleted)
        assertTrue(repo.listForWatch(watchA).isEmpty())
        assertEquals(1, repo.listForWatch(watchB).size)
    }

    @Test
    fun `deleting the last target's reservable prunes the now-empty watch and its poller link`() {
        val watchId = insertWatch()
        val campsiteId = insertCampsite("site-last-target")
        val repo = AvailabilityWatchTargetRepo(ctx)
        repo.replaceForWatch(watchId, listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = null, campsiteId = campsiteId)))
        val poi = insertPoi()
        val pollerRepo = AvailabilityPollerRepo(ctx)
        val pollerId = pollerRepo.upsertActive("test", "parent-last-target", poi, pullNextRunAt = null)
        pollerRepo.linkWatch(watchId, pollerId)

        ctx.execute("DELETE FROM campsites WHERE id = ?", campsiteId)

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
                AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, campsiteId = null),
                AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, campsiteId = null),
            ),
        )

        ctx.execute("DELETE FROM pois WHERE id = ?", poiA)

        val remaining = repo.listForWatch(watchId)
        assertEquals(1, remaining.size)
        assertEquals(poiB, remaining.single().poiId)
        assertTrue(watchExists(watchId))
    }
}
