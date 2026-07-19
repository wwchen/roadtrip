package ca.floo.roadtrip.service.etl.vendors.bcparks

import ca.floo.roadtrip.model.domain.BookingProvider
import ca.floo.roadtrip.model.domain.DataProvider
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.RequestMeta
import ca.floo.roadtrip.model.metadata.ResponseMeta
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BcParksMergeEtlTest {
    private lateinit var ctx: TransformCtx
    private val etl = BcParksMergeEtl()

    @BeforeAll
    fun setUp() {
        val registry = PoiRegistry.loadResource("poi-registry.yaml")
        val tmp = Files.createTempDirectory("bcparks-merge-").toFile()
        tmp.deleteOnExit()
        ctx = TransformCtx.load(tmp, registry)
    }

    @Test
    fun `merge produces campground with Strapi metadata and Aspira booking ref`() {
        val output = etl.transform(etl.parse(bundle()), ctx)

        assertEquals(1, output.campgrounds.size)
        val cg = output.campgrounds.single()
        assertEquals(DataProvider.ASPIRA, cg.dataProvider)
        assertEquals("aspira-4189--2147483548", cg.dataProviderRef)
        assertEquals(BookingProvider.ASPIRA, cg.bookingProvider)
        assertEquals("bc:4189:-2147483548:9001", cg.bookingProviderRef)
        assertEquals("Rathtrevor Beach", cg.name)
        assertEquals(49.3167, cg.latitude)
        assertEquals(-124.2833, cg.longitude)
        assertEquals("<p>A beautiful sandy beach campground.</p>", cg.mediumDescription)
        assertNotNull(cg.photos)
        val photoUrl =
            cg.photos!!
                .jsonArray
                .first()
                .jsonObject["url"]!!
                .jsonPrimitive
                .content
        assertEquals("https://example.test/rathtrevor.jpg", photoUrl)
        assertNotNull(cg.contact)
        val phone =
            cg.contact!!
                .jsonObject["phone"]!!
                .jsonPrimitive
                .content
        assertEquals("250-555-1234", phone)
        val agency =
            cg.management!!
                .jsonObject["agency"]!!
                .jsonPrimitive
                .content
        assertEquals("BC Parks", agency)
    }

    @Test
    fun `merge produces campsites from inventory`() {
        val output = etl.transform(etl.parse(bundle()), ctx)

        assertEquals(1, output.campsites.size)
        val cs = output.campsites.single()
        assertEquals(DataProvider.ASPIRA, cs.dataProvider)
        assertEquals("res-101", cs.dataProviderRef)
        assertEquals(BookingProvider.ASPIRA, cs.bookingProvider)
        assertNotNull(cs.bookingProviderRef)
        assertTrue(cs.bookingProviderRef!!.startsWith("bc:"))
        assertEquals("A12", cs.name)
        assertEquals("Rathtrevor Beach", cs.loopName)
        assertEquals(DataProvider.ASPIRA, cs.parentDataProvider)
        assertEquals("aspira-4189--2147483548", cs.parentDataProviderRef)
    }

    @Test
    fun `skips park container leaves with no resourceLocationId`() {
        val output = etl.transform(etl.parse(bundleWithContainer()), ctx)
        assertEquals(1, output.campgrounds.size)
        assertEquals("Rathtrevor Beach", output.campgrounds.single().name)
    }

    private fun bundle(): InputBundle =
        InputBundle(
            rawCaptures =
                linkedMapOf(
                    "aspira-maps-bc" to listOf(mapsEnvelope()),
                    "bcparks-strapi" to listOf(strapiEnvelope()),
                    "aspira-inventory-bc" to listOf(inventoryEnvelope()),
                    "aspira-dictionaries-bc" to listOf(dictionaryEnvelope()),
                ),
            etlOutputs = linkedMapOf(),
        )

    private fun bundleWithContainer(): InputBundle =
        InputBundle(
            rawCaptures =
                linkedMapOf(
                    "aspira-maps-bc" to listOf(mapsEnvelopeWithContainer()),
                    "bcparks-strapi" to listOf(strapiEnvelope()),
                    "aspira-inventory-bc" to listOf(inventoryEnvelope()),
                    "aspira-dictionaries-bc" to listOf(dictionaryEnvelope()),
                ),
            etlOutputs = linkedMapOf(),
        )

    private fun mapsEnvelope(): Envelope =
        Envelope(
            fetcher = "fetch_aspira_maps",
            fetcherVersion = "1",
            fetchedAt = "2026-07-01T00:00:00Z",
            request = RequestMeta(url = "https://camping.bcparks.ca/api/maps", method = "GET"),
            response = ResponseMeta(status = 200),
            payload =
                Json.parseToJsonElement(
                    """
                    [
                      {
                        "mapId": -2147483548,
                        "transactionLocationId": "4189",
                        "resourceLocationId": "9001",
                        "localizedValues": [
                          {"cultureName": "en-CA", "title": "Rathtrevor Beach"}
                        ],
                        "mapLinks": [],
                        "parentMap": null
                      }
                    ]
                    """.trimIndent(),
                ),
        )

    private fun mapsEnvelopeWithContainer(): Envelope =
        Envelope(
            fetcher = "fetch_aspira_maps",
            fetcherVersion = "1",
            fetchedAt = "2026-07-01T00:00:00Z",
            request = RequestMeta(url = "https://camping.bcparks.ca/api/maps", method = "GET"),
            response = ResponseMeta(status = 200),
            payload =
                Json.parseToJsonElement(
                    """
                    [
                      {
                        "mapId": -100,
                        "transactionLocationId": "9999",
                        "resourceLocationId": null,
                        "localizedValues": [
                          {"cultureName": "en-CA", "title": "Strathcona Park"}
                        ],
                        "mapLinks": [],
                        "parentMap": null
                      },
                      {
                        "mapId": -2147483548,
                        "transactionLocationId": "4189",
                        "resourceLocationId": "9001",
                        "localizedValues": [
                          {"cultureName": "en-CA", "title": "Rathtrevor Beach"}
                        ],
                        "mapLinks": [],
                        "parentMap": null
                      }
                    ]
                    """.trimIndent(),
                ),
        )

    private fun strapiEnvelope(): Envelope =
        Envelope(
            fetcher = "fetch_bcparks_strapi",
            fetcherVersion = "1",
            fetchedAt = "2026-07-01T00:00:00Z",
            request = RequestMeta(url = "https://bcparks.example.test/api/protected-areas", method = "GET"),
            response = ResponseMeta(status = 200),
            payload =
                Json.parseToJsonElement(
                    """
                    {
                      "data": [
                        {
                          "orcs": 1234,
                          "protectedAreaName": "Rathtrevor Beach Provincial Park",
                          "legalStatus": "Active",
                          "latitude": "49.3167",
                          "longitude": "-124.2833",
                          "url": "https://bcparks.ca/rathtrevor-beach/",
                          "description": "<p>A beautiful sandy beach campground.</p>",
                          "parkContact": "250-555-1234",
                          "parkPhotos": [
                            {
                              "imageUrl": "https://example.test/rathtrevor.jpg",
                              "isActive": "true",
                              "isFeatured": "true",
                              "sortOrder": "1"
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

    private fun inventoryEnvelope(): Envelope =
        Envelope(
            fetcher = "fetch_aspira_inventory",
            fetcherVersion = "1",
            fetchedAt = "2026-07-01T00:00:00Z",
            request =
                RequestMeta(
                    url = "https://camping.bcparks.ca/api/resourcelocation/resources",
                    method = "GET",
                ),
            response = ResponseMeta(status = 200),
            payload =
                Json.parseToJsonElement(
                    """
                    {
                      "res-101": {
                        "resourceLocationId": 9001,
                        "resourceCategoryId": 1,
                        "maxCapacity": 6,
                        "mapIds": [-2147483548],
                        "localizedValues": [
                          {"cultureName": "en-CA", "name": "A12"}
                        ]
                      }
                    }
                    """.trimIndent(),
                ),
        )

    private fun dictionaryEnvelope(): Envelope =
        Envelope(
            fetcher = "fetch_aspira_dictionaries",
            fetcherVersion = "1",
            fetchedAt = "2026-07-01T00:00:00Z",
            request = RequestMeta(url = "https://camping.bcparks.ca/api/resourcecategory", method = "GET"),
            response = ResponseMeta(status = 200),
            payload =
                Json.parseToJsonElement(
                    """
                    {
                      "resource_categories": [
                        {
                          "resourceCategoryId": 1,
                          "showResourceCapacityOnline": true,
                          "localizedValues": [{"cultureName": "en-CA", "name": "Campsite"}]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
}
