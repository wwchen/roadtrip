package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.models.domain.ReservableType
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.service.etl.framework.JoinerCtx
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Joiner-level test for [AspiraPoiReservableJoiner]. Seeds both `pois`
 * (aspira-{tenant}-pins-shaped rows with source_id = "aspira-{txn}-{map}")
 * and `reservables` carrying the synthetic parent fields
 * AspiraResourcesEtl writes, runs the adapter, asserts the right pairs.
 *
 * One adapter spans every Aspira tenant — the test exercises all three
 * vendors (aspira_wa / aspira_bc / aspira_pc) plus negative cases.
 */
class AspiraPoiReservableJoinerTest : SharedDbTest() {
    private lateinit var reservablesRepo: ReservableRepo
    private lateinit var joiner: AspiraPoiReservableJoiner

    @BeforeAll
    fun setUp() {
        reservablesRepo = ReservableRepo(ctx)
        joiner = AspiraPoiReservableJoiner()
    }

    @BeforeEach
    fun reset() {
        ctx.execute("TRUNCATE reservable_pois, reservables, pois RESTART IDENTITY CASCADE")
    }

    @Test
    fun `links resource to its parent aspira-pc-pins POI`() {
        val poiId = insertAspiraPoi("aspira-pc-pins", txnLoc = "1001", mapId = "-2147483640")
        val resId = upsertResource(vendorId = "501", txnLoc = "1001", mapId = "-2147483640", vendor = "aspira_pc")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo))

        assertEquals(1, links.size)
        assertEquals(resId, links[0].reservableId)
        assertEquals(poiId, links[0].poiId)
    }

    @Test
    fun `links across all three tenants in one pass`() {
        val pcPoi = insertAspiraPoi("aspira-pc-pins", txnLoc = "1001", mapId = "-2147483640")
        val bcPoi = insertAspiraPoi("aspira-bc-pins", txnLoc = "2001", mapId = "-2147483700")
        val waPoi = insertAspiraPoi("aspira-wa-pins", txnLoc = "3001", mapId = "-2147483800")
        val pcRes = upsertResource("501", "1001", "-2147483640", vendor = "aspira_pc")
        val bcRes = upsertResource("601", "2001", "-2147483700", vendor = "aspira_bc")
        val waRes = upsertResource("701", "3001", "-2147483800", vendor = "aspira_wa")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo))
        val byReservable = links.associate { it.reservableId to it.poiId }

        assertEquals(3, links.size)
        assertEquals(pcPoi, byReservable[pcRes])
        assertEquals(bcPoi, byReservable[bcRes])
        assertEquals(waPoi, byReservable[waRes])
    }

    @Test
    fun `tenant source must match reservable vendor when parent keys collide`() {
        val pcPoi = insertAspiraPoi("aspira-pc-pins", txnLoc = "1001", mapId = "-2147483640")
        val bcPoi = insertAspiraPoi("aspira-bc-pins", txnLoc = "1001", mapId = "-2147483640")
        val pcRes = upsertResource("501", "1001", "-2147483640", vendor = "aspira_pc")
        val bcRes = upsertResource("601", "1001", "-2147483640", vendor = "aspira_bc")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo))
        val byReservable = links.associate { it.reservableId to it.poiId }

        assertEquals(2, links.size)
        assertEquals(pcPoi, byReservable[pcRes])
        assertEquals(bcPoi, byReservable[bcRes])
    }

    @Test
    fun `links via resourceLocationId when txnLoc-mapId rule misses`() {
        // Inventory-driven ETL emits some reservables whose mapIds[0]
        // points at a sub-leaf AspiraLeavesWalk doesn't expose. Those
        // resources have no _parent_aspira_txn_loc / _parent_aspira_map_id,
        // but every site still carries _parent_aspira_resource_loc, and
        // POIs carry resourceLocationId in provider_ref. The fallback
        // join keeps Deception-Pass-shape parks linkable.
        val poiId =
            insertAspiraPoiWithProviderRef(
                source = "aspira-wa-pins",
                txnLoc = "-2147483630",
                mapId = "-2147483388",
                resourceLocationId = "-2147483624",
            )
        val resId =
            upsertResourceByResourceLocationId(
                vendorId = "-2147475889",
                resourceLocationId = "-2147483624",
                vendor = "aspira_wa",
            )

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo))

        assertEquals(1, links.size)
        assertEquals(resId, links[0].reservableId)
        assertEquals(poiId, links[0].poiId)
    }

    @Test
    fun `resourceLocationId fallback respects tenant pairing`() {
        // A WA POI's resourceLocationId could collide with a PC park's;
        // tenant pairing must still bind aspira_wa reservables to
        // aspira-wa-pins POIs only.
        insertAspiraPoiWithProviderRef(
            source = "aspira-pc-pins",
            txnLoc = "9001",
            mapId = "-9000001",
            resourceLocationId = "-2147483624",
        )
        val waPoi =
            insertAspiraPoiWithProviderRef(
                source = "aspira-wa-pins",
                txnLoc = "-2147483630",
                mapId = "-2147483388",
                resourceLocationId = "-2147483624",
            )
        val waRes =
            upsertResourceByResourceLocationId(
                vendorId = "-2147475889",
                resourceLocationId = "-2147483624",
                vendor = "aspira_wa",
            )

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo))

        assertEquals(1, links.size)
        assertEquals(waRes, links[0].reservableId)
        assertEquals(waPoi, links[0].poiId)
    }

    @Test
    fun `mismatched txnLoc or mapId yields no link`() {
        // The composite source_id "aspira-{txnLoc}-{mapId}" must match
        // exactly. Stale captures where one half has rotated produce
        // no link — better than a wrong link.
        insertAspiraPoi("aspira-pc-pins", txnLoc = "1001", mapId = "-2147483640")
        upsertResource(vendorId = "501", txnLoc = "9999", mapId = "-2147483640", vendor = "aspira_pc")
        upsertResource(vendorId = "502", txnLoc = "1001", mapId = "-9999999999", vendor = "aspira_pc")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo))

        assertEquals(0, links.size)
    }

    @Test
    fun `non-aspira vendors are ignored`() {
        // A recgov reservable accidentally carrying aspira-shaped raw
        // shouldn't match an Aspira POI.
        insertAspiraPoi("aspira-pc-pins", txnLoc = "1001", mapId = "-2147483640")
        upsertResource(vendorId = "fake", txnLoc = "1001", mapId = "-2147483640", vendor = "recgov")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo))

        assertEquals(0, links.size)
    }

    @Test
    fun `non-aspira POI sources are ignored`() {
        // A POI under federal-campgrounds with a coincidentally
        // matching source_id must NOT pick up Aspira reservables.
        ctx.execute(
            """
            INSERT INTO pois (source, source_id, category, name, geom, fetched_at)
            VALUES ('federal-campgrounds', 'aspira-1001--2147483640', 'campground', 'collide',
                    ST_SetSRID(ST_MakePoint(-119.5, 37.7), 4326), now())
            """.trimIndent(),
        )
        upsertResource("501", "1001", "-2147483640", vendor = "aspira_pc")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo))

        assertEquals(0, links.size)
    }

    @Test
    fun `soft-deleted POIs are not matched`() {
        val poiId = insertAspiraPoi("aspira-pc-pins", txnLoc = "1001", mapId = "-2147483640")
        ctx.execute("UPDATE pois SET deleted_at = now() WHERE id = ?", poiId)
        upsertResource("501", "1001", "-2147483640", vendor = "aspira_pc")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo))

        assertEquals(0, links.size)
    }

    @Test
    fun `soft-deleted reservables are not matched`() {
        insertAspiraPoi("aspira-pc-pins", txnLoc = "1001", mapId = "-2147483640")
        val resId = upsertResource("501", "1001", "-2147483640", vendor = "aspira_pc")
        ctx.execute("UPDATE reservables SET deleted_at = now() WHERE id = ?", resId)

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo))

        assertEquals(0, links.size)
    }

    @Test
    fun `sweepStaleLinks deletes links whose reservable is no longer active`() {
        val poiId = insertAspiraPoi("aspira-pc-pins", txnLoc = "1001", mapId = "-2147483640")
        val resId = upsertResource("501", "1001", "-2147483640", vendor = "aspira_pc")
        reservablesRepo.linkToPoi(resId, poiId)
        ctx.execute("UPDATE reservables SET deleted_at = now() WHERE id = ?", resId)

        val deleted = joiner.sweepStaleLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo))

        assertEquals(1, deleted)
        assertEquals(0, linkCount())
    }

    @Test
    fun `sweepStaleLinks deletes links whose parent key changed`() {
        val poiId = insertAspiraPoi("aspira-pc-pins", txnLoc = "1001", mapId = "-2147483640")
        val resId = upsertResource("501", "1001", "-2147483640", vendor = "aspira_pc")
        reservablesRepo.linkToPoi(resId, poiId)
        ctx.execute(
            "UPDATE reservables SET provider_ref = ?::jsonb WHERE id = ?",
            """{"transactionLocationId":9999,"mapId":-2147483640}""",
            resId,
        )

        val deleted = joiner.sweepStaleLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo))

        assertEquals(1, deleted)
        assertEquals(0, linkCount())
    }

    @Test
    fun `sweepStaleLinks deletes cross-tenant links even when parent key matches`() {
        val bcPoi = insertAspiraPoi("aspira-bc-pins", txnLoc = "1001", mapId = "-2147483640")
        val pcRes = upsertResource("501", "1001", "-2147483640", vendor = "aspira_pc")
        reservablesRepo.linkToPoi(pcRes, bcPoi)

        val deleted = joiner.sweepStaleLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo))

        assertEquals(1, deleted)
        assertEquals(0, linkCount())
    }

    private fun upsertResource(
        vendorId: String,
        txnLoc: String,
        mapId: String,
        vendor: String,
    ): Long {
        val raw =
            Json.parseToJsonElement(
                """
                {
                  "resource_id":"$vendorId",
                  "_parent_aspira_txn_loc":"$txnLoc",
                  "_parent_aspira_map_id":"$mapId"
                }
                """.trimIndent(),
            )
        return reservablesRepo.upsert(
            ReservableRepo.Input(
                rid = ReservableId(ReservableType.SITE, vendor, vendorId),
                name = null,
                loop = null,
                siteType = null,
                raw = raw,
                providerRef =
                    Json.parseToJsonElement(
                        """{"transactionLocationId":$txnLoc,"mapId":$mapId}""",
                    ),
            ),
        )
    }

    private fun insertAspiraPoi(
        source: String,
        txnLoc: String,
        mapId: String,
    ): Long =
        ctx
            .resultQuery(
                """
                INSERT INTO pois (
                  source, source_id, category, name, geom, fetched_at
                ) VALUES (
                  ?, ?, 'campground', 'aspira-test',
                  ST_SetSRID(ST_MakePoint(-119.5, 37.7), 4326),
                  now()
                ) RETURNING id
                """.trimIndent(),
                source,
                "aspira-$txnLoc-$mapId",
            ).fetchOne()!!
            .get(0, Long::class.java)!!

    private fun insertAspiraPoiWithProviderRef(
        source: String,
        txnLoc: String,
        mapId: String,
        resourceLocationId: String,
    ): Long =
        ctx
            .resultQuery(
                """
                INSERT INTO pois (
                  source, source_id, category, name, geom, fetched_at,
                  provider_ref
                ) VALUES (
                  ?, ?, 'campground', 'aspira-test',
                  ST_SetSRID(ST_MakePoint(-119.5, 37.7), 4326),
                  now(),
                  ?::jsonb
                ) RETURNING id
                """.trimIndent(),
                source,
                "aspira-$txnLoc-$mapId",
                """{"transactionLocationId":$txnLoc,"mapId":$mapId,"resourceLocationId":$resourceLocationId}""",
            ).fetchOne()!!
            .get(0, Long::class.java)!!

    private fun upsertResourceByResourceLocationId(
        vendorId: String,
        resourceLocationId: String,
        vendor: String,
    ): Long {
        // Mirrors the inventory-driven ETL's fallback shape: when the
        // mapIds[0] lookup misses, the ETL writes _parent_aspira_resource_loc
        // (and _parent_aspira_map_id from inv.firstMapId) but no
        // _parent_aspira_txn_loc.
        val raw =
            Json.parseToJsonElement(
                """
                {
                  "resource_id":"$vendorId",
                  "_parent_aspira_resource_loc":"$resourceLocationId"
                }
                """.trimIndent(),
            )
        return reservablesRepo.upsert(
            ReservableRepo.Input(
                rid = ReservableId(ReservableType.SITE, vendor, vendorId),
                name = null,
                loop = null,
                siteType = null,
                raw = raw,
                providerRef =
                    Json.parseToJsonElement(
                        """{"resourceLocationId":$resourceLocationId}""",
                    ),
            ),
        )
    }

    private fun linkCount(): Int =
        ctx
            .fetchOne("SELECT count(*) FROM reservable_pois")!!
            .get(0, Int::class.java)!!
}
