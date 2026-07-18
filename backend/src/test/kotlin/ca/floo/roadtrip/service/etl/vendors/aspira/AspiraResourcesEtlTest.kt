package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.TransformCtx
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
class AspiraResourcesEtlTest {
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
            AspiraResourcesEtl(
                etlSlug = "aspira-wa-resources",
                mapsInputSlug = "aspira-maps-wa",
                inventoryInputSlug = "aspira-inventory-wa",
                vendor = "aspira_wa",
            )

        val dto =
            AspiraResourcesEtl.Parsed(
                inventory = listOf(envelopeOf(inventoryPayload)),
                maps = Json.parseToJsonElement(mapsPayload).jsonObject["payload"] as kotlinx.serialization.json.JsonArray,
                dictionaries = AspiraResourcesEtl.AspiraDictionaries.empty,
            )

        val campsite = etl.transform(dto, ctx).campsites.single()
        val providerRef = campsite.vendorRefPayload!!.jsonObject
        val sourcePayload = campsite.sourcePayload!!.jsonObject

        assertEquals("aspira-wa-pins", campsite.parentVendor)
        assertEquals("aspira--2147483630--2147483388", campsite.parentVendorRefId)
        assertEquals("Deception Pass", campsite.loopName)
        assertEquals(-2147483630, providerRef["transactionLocationId"]!!.jsonPrimitive.long)
        assertEquals(-2147483615, providerRef["mapId"]!!.jsonPrimitive.long)
        assertEquals(-2147483624, providerRef["resourceLocationId"]!!.jsonPrimitive.long)
        assertEquals(-2147483388, sourcePayload["_parent_aspira_map_id"]!!.jsonPrimitive.long)
        assertEquals(-2147483615, sourcePayload["_aspira_resource_map_id"]!!.jsonPrimitive.long)
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
