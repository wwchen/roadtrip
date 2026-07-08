package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ReserveCaliforniaEtlTest {
    @Test
    fun `campground transform emits canonical provider ref with place and facility ids`() {
        val campground =
            ReserveCaliforniaEtl("california-state-parks")
                .transform(catalog(), transformCtx())
                .campgrounds
                .single()

        assertEquals("california-state-parks", campground.vendor)
        assertEquals("rc-690", campground.vendorRefId)
        assertEquals("Emerald Bay SP", campground.name)
        assertEquals("state", campground.kind)
        assertEquals("https://reservecalifornia.com/park/690", campground.reservationUrl)
        assertEquals("Lakefront camping.", campground.mediumDescription)
        assertEquals(
            "https://cdn.example/emerald.jpg",
            campground.photos!!
                .jsonArray
                .first()
                .jsonObject["url"]!!
                .jsonPrimitive
                .content,
        )
        val location = campground.location!!.jsonObject
        assertEquals("CA", location["region"]!!.jsonPrimitive.content)
        assertEquals("US", location["country"]!!.jsonPrimitive.content)
        val management = campground.management!!.jsonObject
        assertEquals("California State Parks", management["agency"]!!.jsonPrimitive.content)
        val providerRef = campground.vendorRefPayload!!.jsonObject
        assertEquals("690", providerRef["place_id"]!!.jsonPrimitive.content)
        assertEquals("611", providerRef["facility_ids"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("612", providerRef["facility_ids"]!!.jsonArray[1].jsonPrimitive.content)
    }

    @Test
    fun `site transform emits canonical campsites linked to california campgrounds`() {
        val campsites =
            ReserveCaliforniaSitesEtl("california-state-park-sites")
                .transform(catalog(), transformCtx())
                .campsites

        val campsite = campsites.single()
        assertEquals("reservecalifornia", campsite.vendor)
        assertEquals("9001", campsite.vendorRefId)
        assertEquals("california-state-parks", campsite.parentVendor)
        assertEquals("rc-690", campsite.parentVendorRefId)
        assertEquals("PINE 001", campsite.name)
        assertEquals("Tent Site", campsite.kind)
        assertEquals("Tent Site", campsite.kindListed)
        assertEquals("Pine Loop", campsite.loopName)

        val providerRef = campsite.vendorRefPayload!!.jsonObject
        assertEquals("9001", providerRef["unit_id"]!!.jsonPrimitive.content)
        assertEquals("611", providerRef["facility_id"]!!.jsonPrimitive.content)
        assertEquals("690", providerRef["_parent_place_id"]!!.jsonPrimitive.content)
        val sourcePayload = campsite.sourcePayload!!.jsonObject
        assertEquals("690", sourcePayload["_parent_place_id"]!!.jsonPrimitive.content)
    }

    private fun catalog(): ReserveCaliforniaCatalog =
        ReserveCaliforniaCatalog(
            places =
                mapOf(
                    690L to
                        ReserveCaliforniaPlace(
                            placeId = 690L,
                            name = "Emerald Bay SP",
                            latitude = 38.954,
                            longitude = -120.094,
                            facilityIds = listOf(611L, 612L),
                            unitTypeByFacilityId = mapOf(611L to "Tent Site", 612L to "Day Use"),
                            imageUrl = "https://cdn.example/emerald.jpg",
                            description = "Lakefront camping.",
                            amenities = listOf("Restrooms"),
                            activities = listOf("Hiking"),
                            raw = jsonObject("""{"PlaceId":690,"Name":"Emerald Bay SP"}"""),
                        ),
                ),
            facilities =
                mapOf(
                    611L to
                        ReserveCaliforniaFacility(
                            facilityId = 611L,
                            placeId = 690L,
                            name = "Pine Loop",
                            facilityTypeNew = 1L,
                            facilityBehaviourType = 1L,
                            allowWebBooking = true,
                            raw = jsonObject("""{"FacilityId":611,"Name":"Pine Loop"}"""),
                        ),
                    612L to
                        ReserveCaliforniaFacility(
                            facilityId = 612L,
                            placeId = 690L,
                            name = "Day Use",
                            facilityTypeNew = 2L,
                            facilityBehaviourType = 1L,
                            allowWebBooking = true,
                            raw = jsonObject("""{"FacilityId":612,"Name":"Day Use"}"""),
                        ),
                ),
            grids =
                mapOf(
                    611L to
                        ReserveCaliforniaGridCatalog(
                            facilityId = 611L,
                            placeId = 690L,
                            facilityName = "Pine Loop",
                            units =
                                listOf(
                                    ReserveCaliforniaUnit(
                                        unitId = 9001L,
                                        name = "PINE 001",
                                        raw = jsonObject("""{"UnitId":9001,"Name":"PINE 001"}"""),
                                    ),
                                ),
                        ),
                    612L to
                        ReserveCaliforniaGridCatalog(
                            facilityId = 612L,
                            placeId = 690L,
                            facilityName = "Day Use",
                            units =
                                listOf(
                                    ReserveCaliforniaUnit(
                                        unitId = 9002L,
                                        name = "DAY 001",
                                        raw = jsonObject("""{"UnitId":9002,"Name":"DAY 001"}"""),
                                    ),
                                ),
                        ),
                ),
            fetchedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    private fun jsonObject(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private fun transformCtx(): TransformCtx {
        val yamlPath =
            File(System.getProperty("user.dir"))
                .resolve("../config/poi-registry.yaml")
                .canonicalFile
        return TransformCtx.load(File("build/tmp/reservecalifornia-etl-test-raw"), PoiRegistry.load(yamlPath))
    }
}
