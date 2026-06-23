package ca.floo.roadtrip.service.etl.vendors.recgov

import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.metadata.Envelope
import ca.floo.roadtrip.models.metadata.RequestMeta
import ca.floo.roadtrip.models.metadata.ResponseMeta
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecGovCampgroundsEtlTest {
    private lateinit var transformCtx: TransformCtx

    @BeforeAll
    fun setUp() {
        val yamlPath =
            File(System.getProperty("user.dir"))
                .resolve("../config/poi-registry.yaml")
                .canonicalFile
        transformCtx = TransformCtx.load(File("build/tmp/recgov-campgrounds-etl-test-raw"), PoiRegistry.load(yamlPath))
    }

    @Test
    fun `transform treats nonreservable RIDB facilities as agency info pages, not RecGov booking targets`() {
        val etl = RecGovCampgroundsEtl("federal-campgrounds")
        val pois = etl.transform(etl.parse(bundle()), transformCtx).associateBy { it.sourceId }

        val reservable = pois.getValue("recgov-232447")
        val reservableRef = assertIs<ProviderRef.RecGov>(reservable.providerRef)
        assertEquals("232447", reservableRef.recgovId)
        assertEquals("https://www.recreation.gov/camping/campgrounds/232447", reservable.infoUrl)

        val nonReservable = pois.getValue("recgov-248965")
        assertNull(nonReservable.providerRef)
        assertEquals("https://www.fs.usda.gov/recarea/lassen/recarea/?recid=11276", nonReservable.infoUrl)
    }

    @Test
    fun `transform promotes RIDB description media activities and recgov rating cell enrichment`() {
        val etl = RecGovCampgroundsEtl("federal-campgrounds")
        val pois = etl.transform(etl.parse(bundle(withEnrichment = true)), transformCtx).associateBy { it.sourceId }

        val upperPines = pois.getValue("recgov-232447")

        assertEquals("National Park Service", upperPines.agency)
        assertEquals("<p>Upper Pines is a Yosemite campground.</p>", upperPines.description)
        assertEquals("https://cdn.example/primary.webp", upperPines.photoUrl)
        assertEquals(listOf("Camping", "Hiking"), upperPines.activities)

        val rating = assertNotNull(upperPines.ratingReviews)
        assertEquals(4.25f, rating.avg)
        assertEquals(8, rating.count)

        val cell = assertNotNull(upperPines.cellCoverage)
        assertEquals(3.5f, cell.getValue("verizon").avg)
        assertEquals(4, cell.getValue("verizon").count)
        assertEquals(1.25f, cell.getValue("att").avg)
        assertEquals(2, cell.getValue("att").count)
    }

    @Test
    fun `transform treats non-scalar agency paths as missing values`() {
        val etl = RecGovCampgroundsEtl("federal-campgrounds")
        val ctx =
            TransformCtx.load(
                rawDir = File("build/tmp/recgov-campgrounds-etl-test-raw"),
                registry =
                    PoiRegistry.load(
                        File("build/tmp/recgov-campgrounds-derived-object-agency.yaml").apply {
                            parentFile.mkdirs()
                            writeText(
                                """
                                data_sources:
                                  - slug: recgov-campgrounds
                                    name: RIDB
                                    fetcher:
                                      executor: python
                                      filename: fetch_recgov.py
                                      output_dir_prefix: recgov-campgrounds
                                poi_data:
                                  - name: Federal Campgrounds
                                    category: campground
                                    subcategory: federal
                                    agency:
                                      derived_from_field: ORGANIZATION[0]
                                    etls:
                                      - slug: federal-campgrounds
                                        adapter: RecGovCampgroundsEtl
                                        inputs: [recgov-campgrounds]
                                """.trimIndent(),
                            )
                        },
                    ),
            )

        val pois = etl.transform(etl.parse(bundle()), ctx)

        assertNull(pois.first { it.sourceId == "recgov-232447" }.agency)
    }

    private fun bundle(withEnrichment: Boolean = false): InputBundle =
        InputBundle(
            rawCaptures =
                linkedMapOf("recgov-campgrounds" to listOf(envelope())).apply {
                    if (withEnrichment) {
                        put("recgov-campground-enrichment", listOf(enrichmentEnvelope()))
                    }
                },
            etlOutputs = linkedMapOf(),
        )

    private fun envelope(): Envelope =
        Envelope(
            fetcher = "fetch_recgov",
            fetcherVersion = "1",
            fetchedAt = "2026-06-17T00:00:00Z",
            request = RequestMeta(url = "https://ridb.recreation.gov/api/v1/facilities", method = "GET"),
            response = ResponseMeta(status = 200),
            payload =
                Json.parseToJsonElement(
                    """
                    {
                      "RECDATA": [
                        {
                          "FacilityID": 232447,
                          "FacilityName": "Upper Pines",
                          "FacilityLatitude": 37.739,
                          "FacilityLongitude": -119.565,
                          "FacilityReservationURL": "https://www.recreation.gov/camping/campgrounds/232447",
                          "FacilityDescription": "<p>Upper Pines is a Yosemite campground.</p>",
                          "Reservable": true,
                          "ACTIVITY": [
                            {"ActivityName": "CAMPING"},
                            {"ActivityName": "HIKING"}
                          ],
                          "MEDIA": [
                            {
                              "URL": "https://cdn.example/preview.webp",
                              "IsPreview": true,
                              "IsPrimary": false
                            },
                            {
                              "URL": "https://cdn.example/primary.webp",
                              "IsPreview": false,
                              "IsPrimary": true
                            }
                          ],
                          "ORGANIZATION": [{"OrgAbbrevName": "NPS", "OrgName": "National Park Service"}],
                          "FACILITYADDRESS": [
                            {
                              "AddressStateCode": "CA",
                              "AddressCountryCode": "USA"
                            }
                          ]
                        },
                        {
                          "FacilityID": 248965,
                          "FacilityName": "Butte Meadows Campground",
                          "FacilityLatitude": 40.078517,
                          "FacilityLongitude": -121.558811,
                          "FacilityReservationURL": "",
                          "Reservable": false,
                          "CAMPSITE": [],
                          "LINK": [
                            {
                              "URL": "https://www.fs.usda.gov/recarea/lassen/recarea/?recid=11276",
                              "Title": "Butte Meadows Campground",
                              "LinkType": "Official Web Site"
                            }
                          ],
                          "ORGANIZATION": [{"OrgAbbrevName": "FS", "OrgName": "USDA Forest Service"}],
                          "FACILITYADDRESS": [
                            {
                              "AddressStateCode": "CA",
                              "AddressCountryCode": "USA"
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

    private fun enrichmentEnvelope(): Envelope =
        Envelope(
            fetcher = "fetch_recgov_campground_enrichment",
            fetcherVersion = "1",
            fetchedAt = "2026-06-17T00:05:00Z",
            request =
                RequestMeta(
                    url = "https://www.recreation.gov/api/ratingreview/aggregate?location_id=232447&location_type=Campground",
                    method = "GET",
                ),
            response = ResponseMeta(status = 200),
            payload =
                Json.parseToJsonElement(
                    """
                    {
                      "facility_id": "232447",
                      "aggregate": {
                        "average_rating": 4.25,
                        "number_of_ratings": 8,
                        "aggregate_cell_coverage_ratings": [
                          {
                            "carrier": "Verizon",
                            "average_rating": 3.5,
                            "number_of_ratings": 4
                          },
                          {
                            "carrier": "AT&T",
                            "average_rating": 1.25,
                            "number_of_ratings": 2
                          },
                          {
                            "carrier": "T-Mobile",
                            "average_rating": null,
                            "number_of_ratings": 0
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                ),
        )
}
