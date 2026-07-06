package ca.floo.roadtrip.repo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PoiServingRepoTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables WHERE source = ?", SOURCE)
        ctx.execute("DELETE FROM pois WHERE source = ?", SOURCE)
    }

    @Test
    fun `detail row derives Aspira CTA ref from linked child map`() {
        val poiId =
            seedPoi(
                """{"transactionLocationId":-2147483647,"mapId":-2147483026,"resourceLocationId":-2147483640}""",
            )
        val laterMap =
            seedReservable(
                vendorId = "-2147479446",
                name = "1",
                providerRefJson = """{"mapId":-2147483639,"resourceLocationId":-2147483640}""",
            )
        val firstMap =
            seedReservable(
                vendorId = "-2147479386",
                name = "C1",
                providerRefJson = """{"mapId":-2147483645,"resourceLocationId":-2147483640}""",
            )
        link(laterMap, poiId)
        link(firstMap, poiId)

        val row = PoiServingRepo(ctx).fetchPoiById(poiId)

        assertNotNull(row)
        val publicRef = Json.parseToJsonElement(row.providerRefJson!!).jsonObject
        val ctaRef = Json.parseToJsonElement(row.ctaProviderRefJson!!).jsonObject
        assertEquals("-2147483026", publicRef["mapId"]!!.jsonPrimitive.content)
        assertEquals("-2147483645", ctaRef["mapId"]!!.jsonPrimitive.content)
        assertEquals("-2147483647", ctaRef["transactionLocationId"]!!.jsonPrimitive.content)
        assertEquals("-2147483640", ctaRef["resourceLocationId"]!!.jsonPrimitive.content)
    }

    private fun seedPoi(providerRefJson: String): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO pois (
                    source, source_id, category, subcategory, agency, name, geom,
                    region, country, properties, provider_ref, info_url, fetched_at
                ) VALUES (
                    ?, 'lake-louise', 'campground', 'federal', 'Parks Canada', 'Lake Louise Campground',
                    ST_SetSRID(ST_MakePoint(-116.18, 51.42), 4326),
                    'AB', 'CA', '{}'::jsonb, ?::jsonb, 'https://reservation.pc.gc.ca/',
                    '2026-07-06 00:00:00+00'::timestamptz
                )
                RETURNING id
                """.trimIndent(),
                SOURCE,
                providerRefJson,
            )!!
            .get("id", Long::class.java)

    private fun seedReservable(
        vendorId: String,
        name: String,
        providerRefJson: String,
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO reservables (
                    type, vendor, vendor_id, source, name, raw, provider_ref
                ) VALUES (
                    'site', 'aspira_pc', ?, ?, ?, '{}'::jsonb, ?::jsonb
                )
                RETURNING id
                """.trimIndent(),
                vendorId,
                SOURCE,
                name,
                providerRefJson,
            )!!
            .get("id", Long::class.java)

    private fun link(
        reservableId: Long,
        poiId: Long,
    ) {
        ctx.execute(
            """
            INSERT INTO reservable_pois (reservable_id, poi_id)
            VALUES (?, ?)
            """.trimIndent(),
            reservableId,
            poiId,
        )
    }

    private companion object {
        const val SOURCE = "poi-serving-test"
    }
}
