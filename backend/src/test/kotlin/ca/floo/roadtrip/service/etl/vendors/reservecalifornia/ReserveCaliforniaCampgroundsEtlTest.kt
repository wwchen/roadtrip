package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

import ca.floo.roadtrip.model.domain.AmenityKey
import ca.floo.roadtrip.model.domain.CampgroundAmenity
import ca.floo.roadtrip.model.domain.CampgroundMetadata
import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.framework.okRecords
import ca.floo.roadtrip.service.etl.framework.records
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReserveCaliforniaCampgroundsEtlTest {
    @Test
    fun `campground transform emits canonical provider ref with place and facility ids`() {
        val campground =
            records(
                ReserveCaliforniaCampgroundsEtl("reservecalifornia-campgrounds")
                    .transform(catalog(), transformCtx()),
            ).single()

        assertEquals(DataProvider.RESERVECALIFORNIA, campground.dataProviderRef.provider)
        assertEquals("690", campground.dataProviderRef.serialize())
        assertEquals("Emerald Bay SP", campground.name)
        assertEquals("state", campground.kind)
        assertEquals("https://reservecalifornia.com/park/690", campground.reservationUrl)
        assertEquals("Lakefront camping.", campground.mediumDescription)
        assertEquals(
            "https://cdn.example/emerald.jpg",
            campground.photos.single().url,
        )
        assertEquals("CA", campground.location.region)
        assertEquals("US", campground.location.country)
        assertEquals("California State Parks", campground.management!!.agency)
    }

    @Test
    fun `highlight labels map onto the amenity vocabulary and activities land in metadata`() {
        val campground =
            records(
                ReserveCaliforniaCampgroundsEtl("reservecalifornia-campgrounds")
                    .transform(catalog(), transformCtx()),
            ).single()

        assertEquals(
            listOf(
                CampgroundAmenity(AmenityKey.TOILETS),
                CampgroundAmenity(AmenityKey.SHOWERS),
                CampgroundAmenity(AmenityKey.CAMP_STORE),
                CampgroundAmenity(AmenityKey.FIRES_ALLOWED),
                CampgroundAmenity(AmenityKey.OTHER, detail = "Museum"),
            ),
            campground.amenities,
        )
        assertEquals(CampgroundMetadata(activities = listOf("Hiking")), campground.metadata)
        assertNull(campground.parentName)
    }

    @Test
    fun `every spec highlight label maps onto its amenity key`() {
        val labelToKey =
            listOf(
                "Restrooms" to AmenityKey.TOILETS,
                "Toilet, Accessible" to AmenityKey.TOILETS,
                "Comfort Station" to AmenityKey.TOILETS,
                "Showers" to AmenityKey.SHOWERS,
                "Rinse Showers" to AmenityKey.SHOWERS,
                "Dump Station" to AmenityKey.DUMP_STATION,
                "Store-convenience" to AmenityKey.CAMP_STORE,
                "Store - Convenience" to AmenityKey.CAMP_STORE,
                "Fire Rings" to AmenityKey.FIRES_ALLOWED,
                "Museum" to AmenityKey.OTHER,
                "  rinse SHOWERS " to AmenityKey.SHOWERS,
            )

        val amenities = highlightAmenities(labelToKey.map { it.first })

        assertEquals(labelToKey.map { it.second }, amenities.map { it.key })
        assertEquals("Museum", amenities[labelToKey.indexOfFirst { it.first == "Museum" }].detail)
    }

    @Test
    fun `site transform emits canonical campsites linked to california campgrounds`() {
        val campsites =
            okRecords(
                ReserveCaliforniaSitesEtl("reservecalifornia-campsites")
                    .transform(catalog(), transformCtx()),
            )

        val campsite = campsites.single()
        assertEquals(DataProvider.RESERVECALIFORNIA, campsite.dataProviderRef.provider)
        assertEquals("9001", campsite.dataProviderRef.serialize())
        val parentDataProviderRef = assertNotNull(campsite.parentDataProviderRef)
        assertEquals(DataProvider.RESERVECALIFORNIA, parentDataProviderRef.provider)
        assertEquals("690", parentDataProviderRef.serialize())
        assertEquals("PINE 001", campsite.name)
        assertEquals(CampsiteKind.TENT, campsite.kind)
        assertEquals("Tent Site", campsite.kindListed)
        assertEquals("Pine Loop", campsite.loopName)

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
                            amenities =
                                listOf(
                                    " restrooms ",
                                    "Rinse Showers",
                                    "Store - Convenience",
                                    "Fire Rings",
                                    "Museum",
                                ),
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

    private fun transformCtx(): TransformCtx =
        TransformCtx.load(File("build/tmp/reservecalifornia-etl-test-raw"), PoiRegistry.loadResource("poi-registry.yaml"))
}
