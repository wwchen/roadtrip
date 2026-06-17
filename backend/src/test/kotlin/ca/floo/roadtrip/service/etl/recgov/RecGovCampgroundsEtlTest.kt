package ca.floo.roadtrip.service.etl.recgov

import ca.floo.roadtrip.models.Envelope
import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.models.RequestMeta
import ca.floo.roadtrip.models.ResponseMeta
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.InputBundle
import ca.floo.roadtrip.service.etl.TransformCtx
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    private fun bundle(): InputBundle =
        InputBundle(
            rawCaptures = linkedMapOf("recgov-campgrounds" to listOf(envelope())),
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
                          "Reservable": true,
                          "ORGANIZATION": [{"OrgAbbrevName": "NPS"}],
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
                          "ORGANIZATION": [{"OrgAbbrevName": "FS"}],
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
}
