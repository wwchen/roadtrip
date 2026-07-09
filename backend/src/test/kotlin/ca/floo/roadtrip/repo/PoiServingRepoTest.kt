package ca.floo.roadtrip.repo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PoiServingRepoTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `detail row surfaces primary campground vendor ref`() {
        val poiId =
            seedPoi(
                providerRefJson = """{"transactionLocationId":-2147483647,"mapId":-2147483026,"resourceLocationId":-2147483640}""",
                propertiesJson = """{"upstream":{"booking_cta_provider_ref":null}}""",
            )

        val row = PoiServingRepo(ctx).fetchPoiById(poiId)

        assertNotNull(row)
        val publicRef = Json.parseToJsonElement(row.providerRefJson!!).jsonObject
        assertEquals("-2147483026", publicRef["mapId"]!!.jsonPrimitive.content)
        assertEquals("-2147483647", publicRef["transactionLocationId"]!!.jsonPrimitive.content)
        assertEquals("-2147483640", publicRef["resourceLocationId"]!!.jsonPrimitive.content)
        assertNull(row.ctaProviderRefJson)
    }

    @Test
    fun `detail row ignores old materialized Aspira CTA ref from source payload`() {
        val poiId =
            seedPoi(
                providerRefJson = """{"transactionLocationId":-2147483647,"mapId":-2147483026,"resourceLocationId":-2147483640}""",
                propertiesJson =
                    """
                    {"upstream":{"booking_cta_provider_ref":{
                      "transactionLocationId":-2147483647,
                      "mapId":-2147483645,
                      "resourceLocationId":-2147483640
                    }}}
                    """.trimIndent(),
            )

        val row = PoiServingRepo(ctx).fetchPoiById(poiId)

        assertNotNull(row)
        assertNull(row.ctaProviderRefJson)
    }

    @Test
    fun `detail row projects first campground link as info URL`() {
        val link = "https://www.fs.usda.gov/recarea/tahoe/recarea/?recid=80728"
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "lake-of-the-woods-campground-192",
                name = "Lake Of The Woods Campground",
                lon = -120.391227722,
                lat = 39.503097534,
                source = SOURCE,
                subcategory = null,
                agency = "USDA Forest Service",
                region = null,
                country = null,
                providerRefJson = """{"campflare_id":"lake-of-the-woods-campground-192"}""",
            )
        ctx.execute(
            "UPDATE campgrounds SET links = ?::jsonb WHERE id = ?",
            """[{"url":"$link","title":"Lake of the Woods"}]""",
            fixture.catalogId,
        )

        val row = PoiServingRepo(ctx).fetchPoiById(fixture.poiId)

        assertNotNull(row)
        assertEquals(link, row.infoUrl)
    }

    @Test
    fun `detail row keeps data source while selecting availability provider ref and booking site`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "recgov-232869",
                name = "Cold Creek",
                lon = -120.3147222,
                lat = 39.5427778,
                source = "federal-campgrounds",
                subcategory = "established",
                agency = "USDA Forest Service",
                region = "CA",
                country = "US",
                providerRefJson = """{"catalog_id":"recgov-232869"}""",
            )
        ctx.execute(
            "UPDATE campgrounds SET reservation_url = ? WHERE id = ?",
            "https://www.recreation.gov/camping/campgrounds/232869",
            fixture.catalogId,
        )
        val recgovVendorRefId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO vendor_refs (
                      vendor, entity_type, external_id, external_name, payload
                    ) VALUES (
                      'recgov', 'campground', '232869', 'Cold Creek', '{"recgov_id":"232869"}'::jsonb
                    )
                    RETURNING id
                    """.trimIndent(),
                )!!
                .get("id", Long::class.java)
        ctx.execute(
            "INSERT INTO campground_vendor_refs (campground_id, vendor_ref_id) VALUES (?, ?)",
            fixture.catalogId,
            recgovVendorRefId,
        )

        val row = PoiServingRepo(ctx).fetchPoiById(fixture.poiId)

        assertNotNull(row)
        assertEquals("federal-campgrounds", row.source)
        assertEquals("recgov-232869", row.sourceId)
        assertEquals("recgov", row.providerSource)
        assertEquals("https://www.recreation.gov/camping/campgrounds/232869", row.reserveUrl)
        val publicRef = Json.parseToJsonElement(row.providerRefJson!!).jsonObject
        assertEquals("232869", publicRef["recgov_id"]!!.jsonPrimitive.content)
    }

    private fun seedPoi(
        providerRefJson: String,
        propertiesJson: String = "{}",
    ): Long =
        ctx
            .seedCatalogPoi(
                sourceId = "lake-louise",
                name = "Lake Louise Campground",
                lon = -116.18,
                lat = 51.42,
                source = SOURCE,
                subcategory = "federal",
                agency = "Parks Canada",
                region = "AB",
                country = "CA",
                providerRefJson = providerRefJson,
                propertiesJson = propertiesJson,
            ).poiId

    private companion object {
        const val SOURCE = "poi-serving-test"
    }
}
