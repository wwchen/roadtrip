package ca.floo.roadtrip.service.etl.vendors.reserveamerica

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
 * Joiner-level test for [ReserveAmericaPoiReservableJoiner]. Seeds POIs
 * (alberta-provincial / new-york-state-parks rows with provider_ref
 * {contract_code, park_id}) and reservables (with the synthetic
 * _parent_contract_code / _parent_park_id fields ReserveAmericaSitesEtl
 * writes), runs the adapter, asserts the pairs. Writes rows directly so the
 * test pins the joiner's two-key match rule, not the upstream wiring.
 */
class ReserveAmericaPoiReservableJoinerTest : SharedDbTest() {
    private lateinit var reservablesRepo: ReservableRepo
    private lateinit var joiner: ReserveAmericaPoiReservableJoiner

    @BeforeAll
    fun setUp() {
        reservablesRepo = ReservableRepo(ctx)
        joiner = ReserveAmericaPoiReservableJoiner()
    }

    @BeforeEach
    fun reset() {
        ctx.execute("TRUNCATE reservable_pois, reservables, pois RESTART IDENTITY CASCADE")
    }

    @Test
    fun `links RA reservable to its park POI by contract and parkId`() {
        val poiId = insertRaPoi(source = "alberta-provincial", contract = "ABPP", parkId = "330101", name = "Aspen Beach")
        val resId = upsertSite(vendor = "reserveamerica_abpp", vendorId = "9001", contract = "ABPP", parkId = "330101")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo))

        assertEquals(1, links.size)
        assertEquals(resId, links[0].reservableId)
        assertEquals(poiId, links[0].poiId)
    }

    @Test
    fun `reservables in different parks map to different POIs`() {
        val aspen = insertRaPoi(source = "alberta-provincial", contract = "ABPP", parkId = "330101", name = "Aspen Beach")
        val woodland = insertRaPoi(source = "new-york-state-parks", contract = "NY", parkId = "489", name = "Woodland Valley")
        val a = upsertSite(vendor = "reserveamerica_abpp", vendorId = "9001", contract = "ABPP", parkId = "330101")
        val b = upsertSite(vendor = "reserveamerica_ny", vendorId = "253478", contract = "NY", parkId = "489")

        val byReservable =
            joiner.discoverLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo)).associate { it.reservableId to it.poiId }

        assertEquals(aspen, byReservable[a])
        assertEquals(woodland, byReservable[b])
    }

    @Test
    fun `same parkId under a different contract does not cross-link`() {
        // parkId collisions across contracts must not link — both keys must match.
        insertRaPoi(source = "alberta-provincial", contract = "ABPP", parkId = "489", name = "AB park 489")
        upsertSite(vendor = "reserveamerica_ny", vendorId = "253478", contract = "NY", parkId = "489")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo))

        assertEquals(0, links.size)
    }

    @Test
    fun `sweepStaleLinks deletes links whose parent key changed`() {
        val poiId = insertRaPoi(source = "alberta-provincial", contract = "ABPP", parkId = "330101", name = "Aspen Beach")
        val resId = upsertSite(vendor = "reserveamerica_abpp", vendorId = "9001", contract = "ABPP", parkId = "330101")
        reservablesRepo.linkToPoi(resId, poiId)
        ctx.execute(
            "UPDATE reservables SET raw = ?::jsonb WHERE id = ?",
            """{"_parent_contract_code":"ABPP","_parent_park_id":"999999"}""",
            resId,
        )

        val deleted = joiner.sweepStaleLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo))

        assertEquals(1, deleted)
        assertEquals(0, linkCount())
    }

    private fun upsertSite(
        vendor: String,
        vendorId: String,
        contract: String,
        parkId: String,
    ): Long {
        val raw = Json.parseToJsonElement("""{"_parent_contract_code":"$contract","_parent_park_id":"$parkId"}""")
        return reservablesRepo.upsert(
            ReservableRepo.Input(
                identity = ReservableId(ReservableType.SITE, vendor, vendorId),
                name = null,
                loop = null,
                siteType = null,
                raw = raw,
            ),
        )
    }

    private fun insertRaPoi(
        source: String,
        contract: String,
        parkId: String,
        name: String,
    ): Long =
        ctx
            .resultQuery(
                """
                INSERT INTO pois (
                  source, source_id, category, name, geom, fetched_at, provider_ref
                ) VALUES (
                  ?, ?, 'campground', ?,
                  ST_SetSRID(ST_MakePoint(-114.0, 52.4), 4326),
                  now(), ?::jsonb
                ) RETURNING id
                """.trimIndent(),
                source,
                "ra-${contract.lowercase()}-$parkId",
                name,
                """{"contract_code":"$contract","park_id":"$parkId"}""",
            ).fetchOne()!!
            .get(0, Long::class.java)!!

    private fun linkCount(): Int =
        ctx
            .fetchOne("SELECT count(*) FROM reservable_pois")!!
            .get(0, Int::class.java)!!
}
