package ca.floo.roadtrip.repo

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the refresh + representative-re-point path.
 *
 * Fixtures skip the CatalogMatcherService and stamp `match_group_id`
 * directly — the invariant a matcher run must leave behind is "all group
 * members share the same match_group_id (= MIN member id)", and this test
 * asserts the *view/repoint* behavior given that invariant, not how it was
 * produced.
 */
class CanonicalViewRepoTest : SharedDbTest() {
    @BeforeEach
    fun resetCatalog() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `refresh + repoint moves POI and watch target off non-winners and is idempotent`() {
        // A is the recgov row and is seeded rich (more populated columns +
        // more child campsites) so it wins the canonical group.
        val recgovCg = seedRichCampgroundRow(name = "Upper Pines", etlSource = "recgov")
        val campflareCg = seedLeanCampgroundRow(name = "Upper Pines", etlSource = "campflare")
        matchAndGroupCampgrounds(recgovCg, campflareCg)

        // Give recgov an extra campsite so its "richness" beats campflare's.
        ctx.seedCampsite(campgroundId = recgovCg, vendor = "recgov", vendorId = "recgov-extra")
        val recgovSite = ctx.seedCampsite(campgroundId = recgovCg, vendor = "recgov", vendorId = "recgov-a")
        val campflareSite = ctx.seedCampsite(campgroundId = campflareCg, vendor = "campflare", vendorId = "cf-a")
        matchAndGroupCampsites(recgovSite, campflareSite)

        // POI lives only on the non-winner (campflare) so re-point must move it.
        val poiId = insertPoi()
        linkPoiToCampground(poiId = poiId, campgroundId = campflareCg)

        // Watch target points at the non-winner campsite.
        val watchId = insertWatch()
        val watchTargetId = insertWatchTarget(watchId = watchId, campsiteId = campflareSite)

        // One availability row on the non-winner campsite too.
        val availabilityId = insertAvailability(campsiteId = campflareSite)

        val repo = CanonicalViewRepo(ctx)
        repo.refreshCanonicalViews()

        // Confirm the canonical views converged on a single winner per group.
        assertEquals(recgovCg, canonicalWinnerId("campground_canonical", groupKey = recgovCg))
        assertEquals(recgovSite, canonicalWinnerId("campsite_canonical", groupKey = recgovSite))

        val stats = repo.repointRepresentatives()

        // POI now sits on the winner campground.
        assertEquals(recgovCg, poiCampgroundId(poiId))
        assertTrue(stats.poisRepointed >= 1)
        // Watch target now points at the winner campsite.
        assertEquals(recgovSite, watchTargetCampsiteId(watchTargetId))
        assertTrue(stats.watchTargetsRepointed >= 1)
        // Availability row now on the winner campsite.
        assertEquals(recgovSite, availabilityCampsiteId(availabilityId))
        assertTrue(stats.availabilityRowsRepointed >= 1)

        // Idempotent — a second call finds no non-winner references left.
        val second = repo.repointRepresentatives()
        assertEquals(0, second.poisRepointed)
        assertEquals(0, second.watchTargetsRepointed)
        assertEquals(0, second.availabilityRowsRepointed)
    }

    @Test
    fun `POI collision collapses to the lower-id POI and soft-deletes the loser`() {
        // Both members are seeded lean, so richness ties and the LOWER
        // campground id wins the tiebreak (DISTINCT ON group_key ORDER BY
        // richness DESC, id ASC).
        val cgA = seedLeanCampgroundRow(name = "Collision Cove", etlSource = "recgov")
        val cgB = seedLeanCampgroundRow(name = "Collision Cove", etlSource = "campflare")
        matchAndGroupCampgrounds(cgA, cgB)
        val winnerCg = minOf(cgA, cgB)
        val loserCg = maxOf(cgA, cgB)

        // Give the LOSER campground its POI FIRST — so the loser-side POI has
        // the lower id and (per the "keep lowest poi id" rule) survives the
        // collapse. This exercises the "src wins" branch that performs an
        // UPDATE + soft-delete-of-winner-poi.
        val poiOnLoser = insertPoi()
        linkPoiToCampground(poiId = poiOnLoser, campgroundId = loserCg)
        val poiOnWinner = insertPoi()
        linkPoiToCampground(poiId = poiOnWinner, campgroundId = winnerCg)
        assertTrue(poiOnLoser < poiOnWinner, "test fixture assumption: loser POI id must be lower")

        val repo = CanonicalViewRepo(ctx)
        repo.refreshCanonicalViews()
        val stats = repo.repointRepresentatives()

        // Exactly one poi_campgrounds row remains: (poiOnLoser, winnerCg).
        val remainingLinks =
            ctx.fetch("SELECT poi_id, campground_id FROM poi_campgrounds")
                .map { it.get("poi_id", Long::class.java) to it.get("campground_id", Long::class.java) }
        assertEquals(listOf(poiOnLoser to winnerCg), remainingLinks)

        // The kept (lower-id) POI is untouched; the higher-id POI is soft-deleted.
        assertNull(poiDeletedAt(poiOnLoser))
        assertNotNull(poiDeletedAt(poiOnWinner))
        // The re-point (UPDATE of the loser-side pc row) is counted.
        assertTrue(stats.poisRepointed >= 1)
    }

    // ----- fixtures / helpers -----

    // Test-only default location — Upper Pines lat/lon, reused across all
    // seeded POIs since these tests never assert on POI geometry.
    private val defaultPoiGeom = """{"type":"Point","coordinates":[-119.56,37.74]}"""

    private fun seedRichCampgroundRow(
        name: String,
        etlSource: String,
    ): Long =
        ctx.fetchOne(
            """
            INSERT INTO campgrounds (
              name, kind, etl_source, status, short_description, medium_description,
              long_description, reservation_url,
              location, amenities, links, photos, price, cell_service, management, contact, connections
            ) VALUES (
              ?, 'campground', ?, 'open', 'Short', 'Medium', 'Long',
              'https://example.test/reserve',
              '{"latitude":37.74,"longitude":-119.56}'::jsonb,
              '{"toilets":true}'::jsonb,
              '[{"kind":"info","url":"https://example.test"}]'::jsonb,
              '[{"url":"https://example.test/photo.jpg"}]'::jsonb,
              '{"currency":"USD"}'::jsonb,
              '{"verizon":"good"}'::jsonb,
              '{"agency":"NPS"}'::jsonb,
              '{"phone":"555-0100"}'::jsonb,
              '{"ridb_facility_id":"232447"}'::jsonb
            )
            RETURNING id
            """.trimIndent(),
            name,
            etlSource,
        )!!.get("id", Long::class.java)

    private fun seedLeanCampgroundRow(
        name: String,
        etlSource: String,
    ): Long =
        ctx.fetchOne(
            """
            INSERT INTO campgrounds (name, kind, etl_source)
            VALUES (?, 'campground', ?)
            RETURNING id
            """.trimIndent(),
            name,
            etlSource,
        )!!.get("id", Long::class.java)

    private fun matchAndGroupCampgrounds(
        aId: Long,
        bId: Long,
    ) {
        val (lo, hi) = minOf(aId, bId) to maxOf(aId, bId)
        ctx.execute(
            """
            INSERT INTO campground_matches (campground_a_id, campground_b_id, heuristic)
            VALUES (?, ?, '{"method":"manual","score":1.0}'::jsonb)
            """.trimIndent(),
            lo,
            hi,
        )
        // Direct UPDATE stands in for a matcher run — collapses (a,b) to a
        // single connected component keyed on MIN(id).
        ctx.execute("UPDATE campgrounds SET match_group_id = ? WHERE id IN (?, ?)", lo, lo, hi)
    }

    private fun matchAndGroupCampsites(
        aId: Long,
        bId: Long,
    ) {
        val (lo, hi) = minOf(aId, bId) to maxOf(aId, bId)
        ctx.execute(
            """
            INSERT INTO campsite_matches (campsite_a_id, campsite_b_id, heuristic)
            VALUES (?, ?, '{"method":"manual","score":1.0}'::jsonb)
            """.trimIndent(),
            lo,
            hi,
        )
        ctx.execute("UPDATE campsites SET match_group_id = ? WHERE id IN (?, ?)", lo, lo, hi)
    }

    private fun insertPoi(): Long =
        ctx.fetchOne(
            """
            INSERT INTO pois (poi_type, geom)
            VALUES ('campground', ST_SetSRID(ST_GeomFromGeoJSON(?), 4326))
            RETURNING id
            """.trimIndent(),
            defaultPoiGeom,
        )!!.get("id", Long::class.java)

    private fun linkPoiToCampground(
        poiId: Long,
        campgroundId: Long,
    ) {
        ctx.execute(
            "INSERT INTO poi_campgrounds (poi_id, campground_id) VALUES (?, ?)",
            poiId,
            campgroundId,
        )
    }

    private fun insertWatch(): Long =
        ctx.fetchOne(
            """
            INSERT INTO availability_watch (start_date, end_date, cadence_sec, trigger_kinds)
            VALUES ('2026-07-04'::date, '2026-07-06'::date, 60, ARRAY['atc'])
            RETURNING id
            """.trimIndent(),
        )!!.get("id", Long::class.java)

    private fun insertWatchTarget(
        watchId: Long,
        campsiteId: Long,
    ): Long =
        ctx.fetchOne(
            """
            INSERT INTO availability_watch_target (watch_id, poi_id, campsite_id)
            VALUES (?, NULL, ?)
            RETURNING id
            """.trimIndent(),
            watchId,
            campsiteId,
        )!!.get("id", Long::class.java)

    private fun insertAvailability(campsiteId: Long): Long =
        ctx.fetchOne(
            """
            INSERT INTO availability (campsite_id, target_date, status, last_observed_at)
            VALUES (?, '2026-07-04'::date, 'available'::availability_status, now())
            RETURNING id
            """.trimIndent(),
            campsiteId,
        )!!.get("id", Long::class.java)

    private fun canonicalWinnerId(
        view: String,
        groupKey: Long,
    ): Long =
        ctx.fetchOne("SELECT id FROM $view WHERE group_key = ?", groupKey)!!
            .get("id", Long::class.java)

    private fun poiCampgroundId(poiId: Long): Long =
        ctx.fetchOne("SELECT campground_id FROM poi_campgrounds WHERE poi_id = ?", poiId)!!
            .get("campground_id", Long::class.java)

    private fun watchTargetCampsiteId(id: Long): Long =
        ctx.fetchOne("SELECT campsite_id FROM availability_watch_target WHERE id = ?", id)!!
            .get("campsite_id", Long::class.java)

    private fun availabilityCampsiteId(id: Long): Long =
        ctx.fetchOne("SELECT campsite_id FROM availability WHERE id = ?", id)!!
            .get("campsite_id", Long::class.java)

    private fun poiDeletedAt(id: Long): Any? =
        ctx.fetchOne("SELECT deleted_at FROM pois WHERE id = ?", id)?.get("deleted_at")
}
