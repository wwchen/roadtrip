package ca.floo.roadtrip.service.etl.vendors.recgov

import ca.floo.roadtrip.model.domain.CampsiteAttribute
import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.RequestMeta
import ca.floo.roadtrip.model.metadata.ResponseMeta
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.framework.terminalRecords
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecGovCampsitesEtlTest {
    private val blankFieldsPayload =
        """
        {
          "campsites": {
            "123456": {
              "site": "",
              "loop": "",
              "campsite_type": "",
              "max_num_people": 8
            }
          }
        }
        """.trimIndent()

    private val promotablePayload =
        """
        {
          "campsites": {
            "123456": {
              "site": "A12",
              "min_num_people": 2,
              "max_num_people": 6,
              "campsite_reserve_type": "Site-Specific",
              "type_of_use": "Overnight",
              "attributes": [
                { "attribute_name": "Fire Pit", "attribute_value": "Yes" },
                { "attribute_name": "Driveway Length", "attribute_value": "40" },
                { "attribute_name": "Shade", "attribute_value": "Partial" }
              ]
            }
          }
        }
        """.trimIndent()

    private val attributeAliasPayload =
        """
        {
          "campsites": {
            "B1": {
              "site": "B1",
              "attributes": [
                { "attribute_name": "Picnic Table", "attribute_value": "No" },
                { "attribute_name": "Accessible", "attribute_value": "Y" },
                { "attribute_name": "Max Num of Vehicles", "attribute_value": "2" },
                { "attribute_name": "Max Vehicle Length", "attribute_value": "27 ft" },
                { "attribute_name": "Pets Allowed", "attribute_value": "" }
              ]
            },
            "B2": {
              "site": "B2",
              "attributes": [
                { "attribute_name": "ada accessible", "attribute_value": "false" },
                { "attribute_name": "Max Vehicles", "attribute_value": "3 cars" }
              ]
            }
          }
        }
        """.trimIndent()

    private val unparseablePromotedPayload =
        """
        {
          "campsites": {
            "C1": {
              "site": "C1",
              "attributes": [
                { "attribute_name": "Fire Pit", "attribute_value": "Seasonal" }
              ]
            }
          }
        }
        """.trimIndent()

    @Test
    fun `transform falls back to campsite id when recgov site fields are blank`() {
        val etl = RecGovCampsitesEtl("recgov-campsites")
        val campsite = terminalRecords(etl, bundle(), transformCtx()).single()

        assertEquals("123456", campsite.dataProviderRef.serialize())
        assertEquals("123456", campsite.name)
        assertEquals(CampsiteKind.OTHER, campsite.kind)
        assertEquals(DataProvider.RECGOV, campsite.parentDataProviderRef!!.provider)
        assertEquals("232447", campsite.parentDataProviderRef!!.serialize())
        assertNull(campsite.kindListed)
        assertNull(campsite.loopName)
    }

    @Test
    fun `promotes recgov attributes onto typed columns and keeps the rest as attributes`() {
        val etl = RecGovCampsitesEtl("recgov-campsites")
        val campsite = terminalRecords(etl, bundle(promotablePayload), transformCtx()).single()

        assertEquals(2, campsite.minPeople)
        assertEquals(6, campsite.maxPeople)
        assertEquals(true, campsite.firepit)
        assertEquals(40, campsite.drivewayLength)
        assertEquals(
            listOf(
                CampsiteAttribute("Shade", "Partial"),
                CampsiteAttribute("Reserve type", "Site-Specific"),
                CampsiteAttribute("Type of use", "Overnight"),
            ),
            campsite.attributes,
        )
        assertNull(campsite.sourcePayload!!.jsonObject["_roadtrip_tags"])
        assertEquals(
            "232447",
            campsite.sourcePayload!!
                .jsonObject["_parent_facility_id"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `promotes both spellings of the vehicle and accessibility attributes`() {
        val etl = RecGovCampsitesEtl("recgov-campsites")
        val campsites = terminalRecords(etl, bundle(attributeAliasPayload), transformCtx()).associateBy { it.name }

        val first = campsites.getValue("B1")
        assertEquals(false, first.picnicTable)
        assertEquals(true, first.adaAccessible)
        assertEquals(2, first.maxCars)
        assertEquals(27, first.maxRvLength)
        assertEquals(listOf(CampsiteAttribute("Pets Allowed", null)), first.attributes)

        val second = campsites.getValue("B2")
        assertEquals(false, second.adaAccessible)
        assertEquals(3, second.maxCars)
    }

    @Test
    fun `keeps a promoted attribute the column cannot hold`() {
        val etl = RecGovCampsitesEtl("recgov-campsites")
        val campsite = terminalRecords(etl, bundle(unparseablePromotedPayload), transformCtx()).single()

        assertNull(campsite.firepit)
        assertEquals(listOf(CampsiteAttribute("Fire Pit", "Seasonal")), campsite.attributes)
    }

    private fun bundle(payloadJson: String = blankFieldsPayload): InputBundle =
        InputBundle(
            rawCaptures = linkedMapOf("recgov-campsites" to listOf(envelope(payloadJson))),
        )

    private fun envelope(payloadJson: String): Envelope =
        Envelope(
            fetcher = "fetch_recgov_campsites",
            fetcherVersion = "1",
            fetchedAt = "2026-06-17T00:00:00Z",
            request =
                RequestMeta(
                    url = "https://www.recreation.gov/api/camps/availability/campground/232447/month?start_date=2026-06-01T00%3A00%3A00Z",
                    method = "GET",
                ),
            response = ResponseMeta(status = 200),
            payload = Json.parseToJsonElement(payloadJson),
        )

    private fun transformCtx(): TransformCtx =
        TransformCtx.load(File("build/tmp/recgov-campsites-etl-test-raw"), PoiRegistry.loadResource("poi-registry.yaml"))
}
