package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.framework.records
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AspiraCampsitesEtlTest {
    private lateinit var ctx: TransformCtx

    private val mapsPayload =
        """
        {
          "payload": [
            {
              "mapId": -2147483346,
              "localizedValues": [
                { "cultureName": "en-US", "title": "Northwest Washington State Parks" }
              ],
              "mapLinks": [
                {
                  "resourceLocationId": -2147483624,
                  "transactionLocationId": -2147483630,
                  "localizations": [
                    { "cultureName": "en-US", "title": "Deception Pass" }
                  ],
                  "childMapId": -2147483388
                }
              ]
            }
          ]
        }
        """.trimIndent()

    private val inventoryPayload =
        """
        {
          "-2147481558": {
            "resourceId": -2147481558,
            "resourceLocationId": -2147483624,
            "resourceCategoryId": -2147483648,
            "localizedValues": [
              { "cultureName": "en-US", "name": "31" }
            ],
            "mapIds": [-2147483615],
            "allowedEquipment": [],
            "definedAttributes": []
          }
        }
        """.trimIndent()

    @BeforeAll
    fun setUp() {
        val registry = PoiRegistry.loadResource("poi-registry.yaml")
        val tmp = Files.createTempDirectory("aspira-resources-").toFile()
        tmp.deleteOnExit()
        ctx = TransformCtx.load(tmp, registry)
    }

    @Test
    fun `links child-map campsite resources to parent campground leaf by resource location id`() {
        val etl =
            AspiraCampsitesEtl(
                etlSlug = "aspira-wa-campsites",
                mapsInputSlug = "aspira-maps-wa",
                inventoryInputSlug = "aspira-inventory-wa",
                aspiraTenant = "wa",
            )

        val dto =
            AspiraCampsitesEtl.Parsed(
                inventory = listOf(envelopeOf(inventoryPayload)),
                maps = Json.parseToJsonElement(mapsPayload).jsonObject["payload"] as kotlinx.serialization.json.JsonArray,
                dictionaries = AspiraCampsitesEtl.AspiraDictionaries.empty,
            )

        val campsite = records(etl.transform(dto, ctx)).single()
        val sourcePayload = campsite.sourcePayload!!.jsonObject

        assertEquals(DataProvider.ASPIRA, campsite.parentDataProviderRef!!.provider)
        assertEquals("-2147483630:-2147483388", campsite.parentDataProviderRef!!.serialize())
        assertEquals("Deception Pass", campsite.loopName)
        assertEquals(-2147483388, sourcePayload["_parent_aspira_map_id"]!!.jsonPrimitive.long)
        assertEquals(-2147483615, sourcePayload["_aspira_resource_map_id"]!!.jsonPrimitive.long)
    }

    @Test
    fun `bc tenant uses STRAPI parent provider ref to match BcParksCampgroundsEtl output`() {
        val etl =
            AspiraCampsitesEtl(
                etlSlug = "aspira-bc-campsites",
                mapsInputSlug = "aspira-maps-bc",
                inventoryInputSlug = "aspira-inventory-bc",
                aspiraTenant = "bc",
                parentDataProvider = DataProvider.STRAPI,
            )

        val dto =
            AspiraCampsitesEtl.Parsed(
                inventory = listOf(envelopeOf(inventoryPayload)),
                maps = Json.parseToJsonElement(mapsPayload).jsonObject["payload"] as kotlinx.serialization.json.JsonArray,
                dictionaries = AspiraCampsitesEtl.AspiraDictionaries.empty,
            )

        val campsite = records(etl.transform(dto, ctx)).single()

        assertEquals(DataProvider.STRAPI, campsite.parentDataProviderRef!!.provider)
        assertEquals("-2147483630:-2147483388", campsite.parentDataProviderRef!!.serialize())
    }

    private fun envelopeOf(payloadJson: String): Envelope =
        Json.decodeFromString(
            Envelope.serializer(),
            """
            { "fetcher": "test", "fetcher_version": "1",
              "fetched_at": "2026-07-05T00:00:00Z",
              "request": { "url": "test://aspira", "method": "GET" },
              "response": { "status": 200 },
              "payload": $payloadJson }
            """.trimIndent(),
        )
}
