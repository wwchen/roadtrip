package ca.floo.roadtrip.service.etl.vendors.recgov

import ca.floo.roadtrip.model.domain.CampgroundMetadata
import ca.floo.roadtrip.model.domain.CampgroundRating
import ca.floo.roadtrip.model.domain.Carrier
import ca.floo.roadtrip.model.domain.CarrierSignal
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.RequestMeta
import ca.floo.roadtrip.model.metadata.ResponseMeta
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.framework.terminalRecords
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.assertEquals
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
        val etl = RecGovCampgroundsEtl("recgov-campgrounds")
        val campgrounds = terminalRecords(etl, bundle(), transformCtx).associateBy { it.dataProviderRef.serialize() }

        val reservable = campgrounds.getValue("232447")
        assertEquals(DataProvider.RECGOV, reservable.dataProviderRef.provider)
        assertEquals("232447", reservable.bookingProviderRef)
        assertEquals("https://www.recreation.gov/camping/campgrounds/232447", reservable.reservationUrl)

        val reservableWithoutUpstreamUrl = campgrounds.getValue("10083567")
        assertEquals("https://www.recreation.gov/camping/campgrounds/10083567", reservableWithoutUpstreamUrl.reservationUrl)

        val nonReservable = campgrounds.getValue("248965")
        assertEquals("https://www.fs.usda.gov/recarea/lassen/recarea/?recid=11276", nonReservable.reservationUrl)
    }

    @Test
    fun `transform promotes RIDB description media activities and recgov rating cell enrichment`() {
        val etl = RecGovCampgroundsEtl("recgov-campgrounds")
        val campgrounds =
            terminalRecords(etl, bundle(withEnrichment = true), transformCtx)
                .associateBy { it.dataProviderRef.serialize() }

        val upperPines = campgrounds.getValue("232447")

        assertEquals("National Park Service", upperPines.management!!.agency)
        assertEquals("<p>Upper Pines is a Yosemite campground.</p>", upperPines.mediumDescription)
        assertEquals(
            "https://cdn.example/primary.webp",
            upperPines.photos.single().url,
        )
        assertEquals(
            CampgroundMetadata(
                activities = listOf("Camping", "Hiking"),
                rating = CampgroundRating(average = 4.25, count = 8),
            ),
            upperPines.metadata,
        )
        // Boost is not in the vocabulary and drops; the rest of the vocabulary reads through.
        assertEquals(
            listOf(
                CarrierSignal(Carrier.VERIZON, average = 3.5, count = 4),
                CarrierSignal(Carrier.ATT, average = 1.25, count = 2),
                CarrierSignal(Carrier.TMOBILE, average = 3.0, count = 5),
                CarrierSignal(Carrier.SPRINT, average = 2.75, count = 6),
                CarrierSignal(Carrier.US_CELLULAR, average = 2.0, count = 3),
            ),
            upperPines.cellService,
        )
        assertEquals("Yosemite National Park", upperPines.parentName)
        assertNull(campgrounds.getValue("10083567").parentName)
    }

    @Test
    fun `transform leaves activities-only metadata without a rating`() {
        val etl = RecGovCampgroundsEtl("recgov-campgrounds")
        val campgrounds = terminalRecords(etl, bundle(), transformCtx).associateBy { it.dataProviderRef.serialize() }

        assertEquals(CampgroundMetadata(activities = listOf("Camping", "Hiking")), campgrounds.getValue("232447").metadata)
        assertNull(campgrounds.getValue("248965").metadata)
        assertEquals(emptyList(), campgrounds.getValue("232447").cellService)
    }

    @Test
    fun `transform treats non-scalar agency paths as missing values`() {
        val etl = RecGovCampgroundsEtl("recgov-campgrounds")
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
                                  - slug: recgov-campgrounds-raw
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
                                      - slug: recgov-campgrounds
                                        adapter: RecGovCampgroundsEtl
                                        inputs: [recgov-campgrounds-raw]
                                """.trimIndent(),
                            )
                        },
                    ),
            )

        val campgrounds = terminalRecords(etl, bundle(), ctx)

        assertNull(campgrounds.first { it.dataProviderRef.serialize() == "232447" }.management)
    }

    private fun bundle(withEnrichment: Boolean = false): InputBundle =
        InputBundle(
            rawCaptures =
                linkedMapOf("recgov-campgrounds-raw" to listOf(envelope())).apply {
                    if (withEnrichment) {
                        put("recgov-campground-enrichment", listOf(enrichmentEnvelope()))
                    }
                },
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
                          "RECAREA": [
                            {"RecAreaID": 2782, "RecAreaName": "Yosemite National Park"},
                            {"RecAreaID": 1234, "RecAreaName": "Ignored Second Parent"}
                          ],
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
                            "average_rating": 3.0,
                            "number_of_ratings": 5
                          },
                          {
                            "carrier": "Sprint",
                            "average_rating": 2.75,
                            "number_of_ratings": 6
                          },
                          {
                            "carrier": "US Cellular",
                            "average_rating": 2.0,
                            "number_of_ratings": 3
                          },
                          {
                            "carrier": "Boost Mobile",
                            "average_rating": 5.0,
                            "number_of_ratings": 1
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                ),
        )
}
