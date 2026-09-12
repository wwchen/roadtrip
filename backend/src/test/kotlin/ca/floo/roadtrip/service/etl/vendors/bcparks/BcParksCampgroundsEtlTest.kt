package ca.floo.roadtrip.service.etl.vendors.bcparks

import ca.floo.roadtrip.model.domain.GeometryProvenance
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.RequestMeta
import ca.floo.roadtrip.model.metadata.ResponseMeta
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.framework.records
import ca.floo.roadtrip.service.etl.framework.terminalRecords
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeaf
import ca.floo.roadtrip.service.etl.vendors.aspira.BcParksStrapiSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BcParksCampgroundsEtlTest {
    private lateinit var ctx: TransformCtx
    private val slug = "aspira-bc-campgrounds"
    private val registry = PoiRegistry.loadResource("poi-registry.yaml")
    private val etl =
        BcParksCampgroundsEtl(
            etlSlug = slug,
            aspiraTenant = "bc",
            geometry =
                checkNotNull(
                    registry.poiData
                        .flatMap { it.etls }
                        .single { it.slug == slug }
                        .geometry,
                ),
        )

    @BeforeAll
    fun setUp() {
        val tmp = Files.createTempDirectory("bcparks-merge-").toFile()
        tmp.deleteOnExit()
        ctx = TransformCtx.load(tmp, registry)
    }

    @Test
    fun `merge produces campground with Strapi metadata and Aspira booking ref`() {
        val output = terminalRecords(etl, bundle(), ctx)

        assertEquals(1, output.size)
        val cg = output.single()
        assertEquals(DataProvider.STRAPI, cg.dataProviderRef.provider)
        assertEquals("4189:-2147483548", cg.dataProviderRef.serialize())
        assertEquals(BookingProvider.ASPIRA, cg.bookingProvider)
        assertEquals("bc:4189:-2147483548:9001", cg.bookingProviderRef)
        assertEquals("Rathtrevor Beach", cg.name)
        assertEquals(49.3167, cg.latitude)
        assertEquals(-124.2833, cg.longitude)
        assertEquals("<p>A beautiful sandy beach campground.</p>", cg.mediumDescription)
        assertEquals("https://example.test/rathtrevor.jpg", cg.photos.single().url)
        assertEquals("250-555-1234", cg.contact!!.phone)
        val agency = cg.management!!.agency
        assertEquals("BC Parks", agency)
        assertNull(cg.parentName)
    }

    @Test
    fun `the merged campground records its geometry provenance and Strapi payload keys`() {
        val cg = terminalRecords(etl, bundle(), ctx).single()

        assertEquals(
            GeometryProvenance(
                matchKind = "exact",
                source = "bcparks-strapi",
                matchedName = "rathtrevor beach",
            ),
            cg.geometryProvenance,
        )
        val payload = cg.sourcePayload!!.jsonObject
        assertNull(payload["match_kind"], "match_kind now lives in geometry_provenance, not the payload blob")
        assertEquals(1234, payload["strapi_orcs"]!!.jsonPrimitive.int)
        assertEquals("https://bcparks.ca/rathtrevor-beach/", payload["strapi_url"]!!.jsonPrimitive.content)
        assertEquals("Rathtrevor Beach", payload["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `skips park container leaves with no resourceLocationId`() {
        val output = terminalRecords(etl, bundleWithContainer(), ctx)
        assertEquals(1, output.size)
        assertEquals("Rathtrevor Beach", output.single().name)
        // The dropped container is still the leaf's parent map, so its title rides along.
        assertEquals("Strathcona Park", output.single().parentName)
    }

    @Test
    fun `a leaf that only fuzzy-matches still carries its matched park's metadata`() {
        val cg = terminalRecords(etl, bundleWithFuzzyLeaf(), ctx).single()

        // "rathtrevor beach west" against "rathtrevor beach": the row is fetched
        // by the name the ladder matched, not by the leaf's own.
        assertEquals("Rathtrevor Beach Campground West", cg.name)
        assertEquals(49.3167, cg.latitude)
        assertEquals(-124.2833, cg.longitude)
        assertEquals("<p>A beautiful sandy beach campground.</p>", cg.mediumDescription)
        assertEquals("250-555-1234", cg.contact!!.phone)

        // Two of three tokens overlap; the provenance carries that score.
        assertEquals(
            GeometryProvenance(
                matchKind = "fuzzy",
                source = "bcparks-strapi",
                matchedName = "rathtrevor beach",
                score = 2.0 / 3.0,
            ),
            cg.geometryProvenance,
        )
    }

    /**
     * The geometry index and the Strapi index are built from one `rows()` list
     * through one keying rule, so a run cannot reach this state; a DTO can.
     */
    @Test
    fun `a matched name with no Strapi row is dropped rather than emitted without metadata`() {
        val bothParks = BcParksStrapiSource(listOf(strapiEnvelopeWithGoldstream()))
        val dto =
            BcParksCampgroundsDto(
                leaves = listOf(leaf("Rathtrevor Beach", mapId = RATHTREVOR_MAP_ID), leaf("Goldstream", mapId = LOOP_MAP_ID)),
                strapiRows = bothParks.rows(),
                geomSources = listOf("bcparks-strapi" to bothParks),
                inventoryEnvelopes = listOf(inventoryEnvelope()),
                dictionaryPayload = dictionaryEnvelope().payload as JsonObject,
            )

        // Both leaves reach the geometry index …
        assertEquals(listOf("Rathtrevor Beach", "Goldstream"), records(etl.transform(dto, ctx)).map { it.name })

        // … so losing only the Strapi row is what drops the second one.
        val rowsMissingGoldstream = dto.copy(strapiRows = BcParksStrapiSource(listOf(strapiEnvelope())).rows())
        assertEquals(listOf("Rathtrevor Beach"), records(etl.transform(rowsMissingGoldstream, ctx)).map { it.name })
    }

    @Test
    fun `booking ref keeps the leaf map when sites span several loop maps`() {
        val cg = terminalRecords(etl, bundleAcrossLoops(), ctx).single()

        // -2147483548 is the leaf's own map; neither loop map covers the whole POI.
        assertEquals("bc:4189:-2147483548:9001", cg.bookingProviderRef)
    }

    private fun leaf(
        name: String,
        mapId: Long,
    ) = AspiraLeaf(
        name = name,
        transactionLocationId = TRANSACTION_LOCATION_ID,
        mapId = mapId,
        resourceLocationId = BOOKABLE_RESOURCE_LOCATION_ID,
    )

    private fun bundleWithFuzzyLeaf(): InputBundle =
        InputBundle(
            rawCaptures =
                linkedMapOf(
                    "aspira-maps-bc" to listOf(mapsEnvelopeWithFuzzyLeaf()),
                    "bcparks-strapi" to listOf(strapiEnvelope()),
                    "aspira-inventory-bc" to listOf(inventoryEnvelope()),
                    "aspira-dictionaries-bc" to listOf(dictionaryEnvelope()),
                ),
        )

    private fun bundleAcrossLoops(): InputBundle =
        InputBundle(
            rawCaptures =
                linkedMapOf(
                    "aspira-maps-bc" to listOf(mapsEnvelope()),
                    "bcparks-strapi" to listOf(strapiEnvelope()),
                    "aspira-inventory-bc" to listOf(inventoryEnvelopeAcrossLoops()),
                    "aspira-dictionaries-bc" to listOf(dictionaryEnvelope()),
                ),
        )

    private fun bundle(): InputBundle =
        InputBundle(
            rawCaptures =
                linkedMapOf(
                    "aspira-maps-bc" to listOf(mapsEnvelope()),
                    "bcparks-strapi" to listOf(strapiEnvelope()),
                    "aspira-inventory-bc" to listOf(inventoryEnvelope()),
                    "aspira-dictionaries-bc" to listOf(dictionaryEnvelope()),
                ),
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
                        "parentMap": {"mapId": -100}
                      }
                    ]
                    """.trimIndent(),
                ),
        )

    /** One leaf whose normalized name ("rathtrevor beach west") only overlaps the Strapi park's. */
    private fun mapsEnvelopeWithFuzzyLeaf(): Envelope =
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
                          {"cultureName": "en-CA", "title": "Rathtrevor Beach Campground West"}
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

    /** [strapiEnvelope] plus a second park, so a geometry index built from it out-keys the rows. */
    private fun strapiEnvelopeWithGoldstream(): Envelope =
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
                          "latitude": "49.3167",
                          "longitude": "-124.2833"
                        },
                        {
                          "orcs": 5678,
                          "protectedAreaName": "Goldstream Provincial Park",
                          "latitude": "48.4756",
                          "longitude": "-123.5533"
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

    /** Two bookable sites on sibling loop maps, neither of them the leaf's own map. */
    private fun inventoryEnvelopeAcrossLoops(): Envelope =
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
                      "res-201": {
                        "resourceLocationId": 9001,
                        "resourceCategoryId": 1,
                        "maxCapacity": 6,
                        "mapIds": [-2147483547],
                        "localizedValues": [
                          {"cultureName": "en-CA", "name": "A12"}
                        ]
                      },
                      "res-202": {
                        "resourceLocationId": 9001,
                        "resourceCategoryId": 1,
                        "maxCapacity": 6,
                        "mapIds": [-2147483546],
                        "localizedValues": [
                          {"cultureName": "en-CA", "name": "B7"}
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

    private companion object {
        const val TRANSACTION_LOCATION_ID = 4189L
        const val BOOKABLE_RESOURCE_LOCATION_ID = 9001L
        const val RATHTREVOR_MAP_ID = -2147483548L
        const val LOOP_MAP_ID = -2147483547L
    }
}
