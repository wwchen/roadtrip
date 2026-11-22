package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

import ca.floo.roadtrip.model.domain.AmenityKey
import ca.floo.roadtrip.model.domain.CampgroundAmenity
import ca.floo.roadtrip.model.domain.CampgroundMetadata
import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.metadata.TransformResult
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
                "Flush Toilets" to AmenityKey.TOILETS,
                "Solar Showers" to AmenityKey.SHOWERS,
                "Convenience Store" to AmenityKey.CAMP_STORE,
                "Fire Pit" to AmenityKey.FIRES_ALLOWED,
                "Potable Water" to AmenityKey.WATER,
                "Water" to AmenityKey.WATER,
                "Central Water Access" to AmenityKey.WATER,
                "Museum" to AmenityKey.OTHER,
                "  rinse SHOWERS " to AmenityKey.SHOWERS,
            )

        assertEquals(
            labelToKey.map { it.second },
            labelToKey.map { (label, _) -> highlightAmenities(listOf(label)).single().key },
        )
        assertEquals("Museum", highlightAmenities(listOf("Museum")).single().detail)
    }

    @Test
    fun `two highlights naming the same amenity collapse to one`() {
        assertEquals(
            listOf(CampgroundAmenity(AmenityKey.TOILETS)),
            highlightAmenities(listOf("Restrooms", "Comfort Station")),
        )
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

    @Test
    fun `each unit takes the kind of its own unit type rather than its facility's first`() {
        val base = catalog()
        val pineLoop = base.grids.getValue(611L)
        val units =
            listOf(
                pineLoop.units.single(),
                ReserveCaliforniaUnit(9003L, "PINE 003", CAMPSITE_TYPE, jsonObject("""{"UnitId":9003}""")),
                ReserveCaliforniaUnit(9004L, "PINE 004", UNLISTED_TYPE, jsonObject("""{"UnitId":9004}""")),
                ReserveCaliforniaUnit(9005L, "PINE 005", null, jsonObject("""{"UnitId":9005}""")),
            )
        val results =
            ReserveCaliforniaSitesEtl("reservecalifornia-campsites")
                .transform(base.copy(grids = mapOf(611L to pineLoop.copy(units = units))), transformCtx())
                .filterIsInstance<TransformResult.Ok<CampsiteUpsertCandidate>>()
                .toList()

        assertEquals(
            listOf(
                Triple(CampsiteKind.TENT, "Tent Site", emptyList()),
                Triple(CampsiteKind.STANDARD, "Campsite", emptyList()),
                Triple(
                    CampsiteKind.OTHER,
                    null,
                    listOf("ReserveCalifornia unit type $UNLISTED_TYPE not listed by place 690 facility 611"),
                ),
                Triple(CampsiteKind.OTHER, null, listOf("ReserveCalifornia unit has no UnitTypeId")),
            ),
            results.map { Triple(it.record.kind, it.record.kindListed, it.warnings) },
        )
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
                            unitTypeNamesByFacilityId =
                                mapOf(
                                    611L to mapOf(TENT_SITE_TYPE to "Tent Site", CAMPSITE_TYPE to "Campsite"),
                                    612L to mapOf(DAY_USE_TYPE to "Day Use"),
                                ),
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
                                        unitTypeId = TENT_SITE_TYPE,
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
                                        unitTypeId = DAY_USE_TYPE,
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

    private companion object {
        const val TENT_SITE_TYPE = 4328L
        const val CAMPSITE_TYPE = 4303L
        const val DAY_USE_TYPE = 4304L
        const val UNLISTED_TYPE = 9999L
    }
}
