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
