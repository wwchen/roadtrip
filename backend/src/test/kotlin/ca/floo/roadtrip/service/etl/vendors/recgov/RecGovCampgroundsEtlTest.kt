package ca.floo.roadtrip.service.etl.vendors.recgov

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
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecGovCampgroundsEtlTest {
    private lateinit var transformCtx: TransformCtx

    @BeforeAll
    fun setUp() {
        transformCtx =
            TransformCtx.load(
                File("build/tmp/recgov-campgrounds-etl-test-raw"),
                PoiRegistry.loadResource("poi-registry.yaml"),
            )
    }

    @Test
    fun `transform treats nonreservable RIDB facilities as agency info pages, not RecGov booking targets`() {
        val etl = RecGovCampgroundsEtl("federal-campgrounds")
        val campgrounds = etl.transform(etl.parse(bundle()), transformCtx).campgrounds.associateBy { it.dataProviderRef }

        val reservable = campgrounds.getValue("recgov-232447")
        assertEquals("recgov", reservable.dataProvider)
        assertEquals("https://www.recreation.gov/camping/campgrounds/232447", reservable.reservationUrl)

        val reservableWithoutUpstreamUrl = campgrounds.getValue("recgov-10083567")
        assertEquals("https://www.recreation.gov/camping/campgrounds/10083567", reservableWithoutUpstreamUrl.reservationUrl)

        val nonReservable = campgrounds.getValue("recgov-248965")
        assertEquals("https://www.fs.usda.gov/recarea/lassen/recarea/?recid=11276", nonReservable.reservationUrl)
    }

    @Test
    fun `transform promotes RIDB description media activities and recgov rating cell enrichment`() {
        val etl = RecGovCampgroundsEtl("federal-campgrounds")
        val campgrounds =
            etl
                .transform(
                    etl.parse(bundle(withEnrichment = true)),
                    transformCtx,
                ).campgrounds
                .associateBy { it.dataProviderRef }

        val upperPines = campgrounds.getValue("recgov-232447")

        val management = upperPines.management!!.jsonObject
        assertEquals("National Park Service", management["agency"]!!.jsonPrimitive.content)
        assertEquals("<p>Upper Pines is a Yosemite campground.</p>", upperPines.mediumDescription)
        assertEquals(
            "https://cdn.example/primary.webp",
            upperPines.photos!!
                .jsonArray
                .first()
                .jsonObject["url"]!!
                .jsonPrimitive
                .content,
        )
        val metadata = upperPines.metadata!!.jsonObject
        assertEquals("Camping", metadata["activities"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("Hiking", metadata["activities"]!!.jsonArray[1].jsonPrimitive.content)

        val rating = assertNotNull(metadata["rating_reviews"]).jsonObject
        assertEquals("4.25", rating["avg"]!!.jsonPrimitive.content)
        assertEquals("8", rating["count"]!!.jsonPrimitive.content)

        val cell = assertNotNull(upperPines.cellService).jsonObject
        val verizon = cell.getValue("verizon").jsonObject
        assertEquals("3.5", verizon["avg"]!!.jsonPrimitive.content)
        assertEquals("4", verizon["count"]!!.jsonPrimitive.content)
        val att = cell.getValue("att").jsonObject
        assertEquals("1.25", att["avg"]!!.jsonPrimitive.content)
        assertEquals("2", att["count"]!!.jsonPrimitive.content)
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
                                  - name: Rec.gov Campgrounds
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

        val campgrounds = etl.transform(etl.parse(bundle()), ctx).campgrounds

        assertNull(campgrounds.first { it.dataProviderRef == "recgov-232447" }.management)
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
                          "FacilityID": 10083567,
                          "FacilityName": "White Wolf",
                          "FacilityLatitude": 37.869,
                          "FacilityLongitude": -119.647,
                          "FacilityReservationURL": "",
                          "Reservable": true,
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
