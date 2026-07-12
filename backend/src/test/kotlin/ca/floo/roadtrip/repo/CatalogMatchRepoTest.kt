package ca.floo.roadtrip.repo

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Repo-level tests for CatalogMatchRepo. Seeds catalog rows directly (skipping
 * the fixture helper because it doesn't yet set data_source) so we can exercise
 * shared-vendor-ref detection, upsert idempotence, and label propagation.
 */
class CatalogMatchRepoTest : SharedDbTest() {
    @BeforeEach
    fun resetCatalog() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `sharedVendorRefCampgroundPairs returns normalized pair when two campgrounds share vendor ref by triple`() {
        val recgovId = seedCampgroundRow(name = "Upper Pines", dataSource = "recgov")
        val campflareId = seedCampgroundRow(name = "Upper Pines", dataSource = "campflare")
        val vendorRefId =
            seedVendorRefRow(
                vendor = "recgov",
                entityType = "campground",
                externalId = "232447",
            )
        // The single vendor_ref is linked to both campground rows via the
        // *_vendor_refs join table — exactly what happens when Campflare's
        // additionalVendorRefs points at a recgov id that already has its own
        // recgov catalog row.
        linkCampgroundVendorRef(campgroundId = recgovId, vendorRefId = vendorRefId)
        linkCampgroundVendorRef(campgroundId = campflareId, vendorRefId = vendorRefId)

        val repo = CatalogMatchRepo(ctx)
        val pairs = repo.sharedVendorRefCampgroundPairs()

        assertEquals(1, pairs.size)
        val pair = pairs.single()
        assertTrue(pair.aId < pair.bId, "pair should be normalized aId<bId (got aId=${pair.aId}, bId=${pair.bId})")
        assertEquals(minOf(recgovId, campflareId), pair.aId)
        assertEquals(maxOf(recgovId, campflareId), pair.bId)
        assertEquals(JsonPrimitive("shared_vendor_ref"), pair.heuristic["method"])
        assertEquals(JsonPrimitive("recgov"), pair.heuristic["vendor"])
        assertEquals(JsonPrimitive("232447"), pair.heuristic["external_id"])
    }

    @Test
    fun `upsertCampgroundMatches is idempotent and updates heuristic on conflict`() {
        val aId = seedCampgroundRow(name = "A", dataSource = "recgov")
        val bId = seedCampgroundRow(name = "B", dataSource = "campflare")
        val (lo, hi) = minOf(aId, bId) to maxOf(aId, bId)
        val repo = CatalogMatchRepo(ctx)

        val firstHeuristic =
            buildJsonObject {
                put("method", JsonPrimitive("shared_vendor_ref"))
                put("score", JsonPrimitive(1.0))
            }
        val upsertedFirst = repo.upsertCampgroundMatches(listOf(CatalogMatchRepo.MatchPair(lo, hi, firstHeuristic)))
        assertEquals(1, upsertedFirst)
        val firstUpdatedAt = fetchMatchUpdatedAt(lo, hi)
        val firstStored = fetchMatchHeuristic(lo, hi)
        assertEquals("shared_vendor_ref", firstStored)

        // Sleep a tick so updated_at can advance detectably.
        Thread.sleep(SLEEP_TO_ADVANCE_CLOCK_MS)

        val secondHeuristic =
            buildJsonObject {
                put("method", JsonPrimitive("geo_name"))
                put("score", JsonPrimitive(0.9))
            }
        val upsertedSecond = repo.upsertCampgroundMatches(listOf(CatalogMatchRepo.MatchPair(lo, hi, secondHeuristic)))
        assertEquals(1, upsertedSecond)
        assertEquals(1, tableCount("campground_matches"))
        val secondUpdatedAt = fetchMatchUpdatedAt(lo, hi)
        val secondStored = fetchMatchHeuristic(lo, hi)
        assertEquals("geo_name", secondStored)
        assertNotEquals(firstUpdatedAt, secondUpdatedAt)
    }

    @Test
    fun `recomputeMatchGroups collapses transitive matches to component minimum`() {
        val aId = seedCampgroundRow(name = "A", dataSource = "recgov")
        val bId = seedCampgroundRow(name = "B", dataSource = "campflare")
        val cId = seedCampgroundRow(name = "C", dataSource = "aspira")
        val dId = seedCampgroundRow(name = "D", dataSource = "recgov")
        insertCampgroundMatch(aId, bId, method = "shared_vendor_ref")
        insertCampgroundMatch(bId, cId, method = "shared_vendor_ref")
        val repo = CatalogMatchRepo(ctx)

        val rowsUpdated = repo.recomputeMatchGroups()
        assertTrue(rowsUpdated > 0, "expected at least the seed pass to touch rows")

        assertEquals(aId, matchGroupOf("campgrounds", aId))
        assertEquals(aId, matchGroupOf("campgrounds", bId))
        assertEquals(aId, matchGroupOf("campgrounds", cId))
        // The unmatched row is grouped by COALESCE(match_group_id, id) in the
        // canonical views; it does not need a stored singleton group.
        assertNull(matchGroupOf("campgrounds", dId))

        val secondRowsUpdated = repo.recomputeMatchGroups()
        assertEquals(0, secondRowsUpdated, "stable recompute should not rewrite converged groups")
        assertEquals(aId, matchGroupOf("campgrounds", aId))
        assertEquals(aId, matchGroupOf("campgrounds", bId))
        assertEquals(aId, matchGroupOf("campgrounds", cId))
        assertNull(matchGroupOf("campgrounds", dId))
    }

    @Test
    fun `recomputeMatchGroups merges later bridge match without reseeding stable groups`() {
        val aId = seedCampgroundRow(name = "A", dataSource = "recgov")
        val bId = seedCampgroundRow(name = "B", dataSource = "campflare")
        val cId = seedCampgroundRow(name = "C", dataSource = "aspira")
        val dId = seedCampgroundRow(name = "D", dataSource = "reserveamerica")
        val repo = CatalogMatchRepo(ctx)

        insertCampgroundMatch(aId, bId, method = "shared_vendor_ref")
        repo.recomputeMatchGroups()
        assertEquals(aId, matchGroupOf("campgrounds", aId))
        assertEquals(aId, matchGroupOf("campgrounds", bId))

        insertCampgroundMatch(cId, dId, method = "shared_vendor_ref")
        repo.recomputeMatchGroups()
        assertEquals(cId, matchGroupOf("campgrounds", cId))
        assertEquals(cId, matchGroupOf("campgrounds", dId))

        insertCampgroundMatch(bId, cId, method = "shared_vendor_ref")
        val bridgeRowsUpdated = repo.recomputeMatchGroups()

        assertTrue(bridgeRowsUpdated > 0, "bridge match should lower the second component")
        assertEquals(aId, matchGroupOf("campgrounds", aId))
        assertEquals(aId, matchGroupOf("campgrounds", bId))
        assertEquals(aId, matchGroupOf("campgrounds", cId))
        assertEquals(aId, matchGroupOf("campgrounds", dId))
        assertEquals(0, repo.recomputeMatchGroups(), "merged components should remain stable")
    }

    private fun seedCampgroundRow(
        name: String,
        dataSource: String,
    ): Long {
        // V40 requires every campground row to have an owning vendor_ref.
        // A synthetic primary ref per seed keeps these matcher tests
        // decoupled from the specific ref shape they exercise via
        // seedVendorRefRow/linkCampgroundVendorRef further down.
        val primaryRefId =
            seedVendorRefRow(
                vendor = dataSource,
                entityType = "campground",
                externalId = "seed-primary:$dataSource:$name",
            )
        val campgroundId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO campgrounds (name, kind, data_source, primary_vendor_ref_id)
                    VALUES (?, 'campground', ?, ?)
                    RETURNING id
                    """.trimIndent(),
                    name,
                    dataSource,
                    primaryRefId,
                )!!
                .get("id", Long::class.java)
        linkCampgroundVendorRef(campgroundId, primaryRefId)
        return campgroundId
    }

    private fun seedVendorRefRow(
        vendor: String,
        entityType: String,
        externalId: String,
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO vendor_refs (vendor, entity_type, external_id)
                VALUES (?, ?, ?)
                RETURNING id
                """.trimIndent(),
                vendor,
                entityType,
                externalId,
            )!!
            .get("id", Long::class.java)

    private fun linkCampgroundVendorRef(
        campgroundId: Long,
        vendorRefId: Long,
    ) {
        ctx.execute(
            "INSERT INTO campground_vendor_refs (campground_id, vendor_ref_id) VALUES (?, ?)",
            campgroundId,
            vendorRefId,
        )
    }

    private fun insertCampgroundMatch(
        idA: Long,
        idB: Long,
        method: String,
    ) {
        val (lo, hi) = minOf(idA, idB) to maxOf(idA, idB)
        val heuristic = """{"method":"$method","score":1.0}"""
        ctx.execute(
            """
            INSERT INTO campground_matches (campground_a_id, campground_b_id, heuristic)
            VALUES (?, ?, ?::jsonb)
            """.trimIndent(),
            lo,
            hi,
            heuristic,
        )
    }

    private fun fetchMatchUpdatedAt(
        aId: Long,
        bId: Long,
    ): OffsetDateTime =
        ctx
            .fetchOne(
                "SELECT updated_at FROM campground_matches WHERE campground_a_id = ? AND campground_b_id = ?",
                aId,
                bId,
            )!!
            .get("updated_at", OffsetDateTime::class.java)

    private fun fetchMatchHeuristic(
        aId: Long,
        bId: Long,
    ): String =
        ctx
            .fetchOne(
                "SELECT heuristic->>'method' AS method FROM campground_matches WHERE campground_a_id = ? AND campground_b_id = ?",
                aId,
                bId,
            )!!
            .get("method", String::class.java)

    private fun matchGroupOf(
        table: String,
        id: Long,
    ): Long? =
        ctx
            .fetchOne("SELECT match_group_id FROM $table WHERE id = ?", id)!!
            .get("match_group_id", Long::class.javaObjectType)

    private fun tableCount(table: String): Int =
        ctx
            .fetchOne("SELECT COUNT(*) AS n FROM $table")!!
            .get("n", Number::class.java)
            .toInt()

    companion object {
        // Postgres now() has microsecond precision; a small sleep guarantees
        // updated_at advances between two updates on the same row without
        // depending on wall-clock resolution.
        private const val SLEEP_TO_ADVANCE_CLOCK_MS: Long = 10
    }
}
