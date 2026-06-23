package ca.floo.roadtrip.models.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PoiTest {
    @Test
    fun `campground properties serialize sparse dto fields and upstream payload`() {
        val poi =
            campground(
                amenities = listOf("showers", "water"),
                activities = listOf("hiking"),
                sites = 42,
                description = "Camp among redwoods.",
                photoUrl = "https://example.test/photo.jpg",
                cellCoverage =
                    mapOf(
                        "verizon" to CellSignal(avg = 3.5f, count = 4),
                    ),
                ratingReviews = RatingSummary(avg = 4.25f, count = 8),
                subcategory = "federal",
                agency = "NPS",
                extras = Json.parseToJsonElement("""{"description":"quoted \"text\""}"""),
            )
        val properties = poi.propertiesJson()

        assertAgency(poi, "NPS")
        assertEquals("showers", properties["amenities"]!!.jsonArray[0].jsonPrimitive.content)
        val activity = properties["activities"]!!.jsonArray.single().jsonPrimitive
        val upstream = properties["upstream"]!!.jsonObject

        assertEquals("hiking", activity.content)
        assertEquals(42, properties["sites"]!!.jsonPrimitive.int)
        assertEquals("Camp among redwoods.", properties["description"]!!.jsonPrimitive.content)
        assertEquals("https://example.test/photo.jpg", properties["photo_url"]!!.jsonPrimitive.content)
        val cellCoverage = properties["cell_coverage"]!!.jsonObject
        assertEquals(3.5, cellCoverage["verizon"]!!.jsonArray[0].jsonPrimitive.double)
        assertEquals(4, cellCoverage["verizon"]!!.jsonArray[1].jsonPrimitive.int)
        val ratingReviews = properties["rating_reviews"]!!.jsonArray
        assertEquals(4.25, ratingReviews[0].jsonPrimitive.double)
        assertEquals(8, ratingReviews[1].jsonPrimitive.int)
        assertEquals("federal", properties["subcategory"]!!.jsonPrimitive.content)
        assertNull(properties["agency"])
        assertEquals("quoted \"text\"", upstream["description"]!!.jsonPrimitive.content)
        assertNull(properties["season"])
        assertNull(properties["near"])
    }

    @Test
    fun `supercharger properties omit empty pricebooks and serialize non-empty pricebooks`() {
        val withoutPricebooks = supercharger(pricebooks = emptyList()).propertiesJson()
        val chargerWithPricebooks =
            supercharger(
                pricebooks =
                    listOf(
                        Json.parseToJsonElement("""{"currencyCode":"USD","rate":0.28}"""),
                    ),
            )
        val withPricebooks = chargerWithPricebooks.propertiesJson()

        assertEquals(12, withoutPricebooks["stall_count"]!!.jsonPrimitive.int)
        assertEquals(250, withoutPricebooks["max_power_kw"]!!.jsonPrimitive.int)
        assertNull(withoutPricebooks["agency"])
        assertNull(withoutPricebooks["pricebooks"])
        val pricebook = withPricebooks["pricebooks"]!!.jsonArray[0].jsonObject
        assertEquals("USD", pricebook["currencyCode"]!!.jsonPrimitive.content)
        assertEquals(0.28, pricebook["rate"]!!.jsonPrimitive.double)
    }

    @Test
    fun `planet fitness properties serialize to empty object when no optional fields are present`() {
        val poi =
            Poi.PlanetFitness(
                source = "planet-fitness",
                sourceId = "pf-1",
                name = "Planet Fitness",
                geomGeoJson = POINT_GEO_JSON,
                region = "WA",
                country = "US",
                phone = null,
                address = null,
                infoUrl = null,
                fetchedAt = FETCHED_AT,
                lastVerified = null,
                agency = "Planet Fitness",
                openingHours = null,
            )
        val properties = poi.propertiesJson()

        assertEquals(emptySet(), properties.keys)
    }

    private fun assertAgency(
        poi: Poi,
        expected: String?,
    ) {
        assertEquals(expected, poi.agency)
    }

    private fun campground(
        amenities: List<String> = emptyList(),
        activities: List<String> = emptyList(),
        sites: Int? = null,
        description: String? = null,
        photoUrl: String? = null,
        cellCoverage: Map<String, CellSignal>? = null,
        ratingReviews: RatingSummary? = null,
        subcategory: String? = null,
        agency: String? = null,
        extras: JsonElement? = null,
    ): Poi.Campground =
        Poi.Campground(
            source = "campgrounds",
            sourceId = "campground-1",
            name = "Campground",
            geomGeoJson = POINT_GEO_JSON,
            region = "BC",
            country = "CA",
            phone = null,
            address = null,
            infoUrl = null,
            fetchedAt = FETCHED_AT,
            lastVerified = null,
            providerRef = null,
            amenities = amenities,
            activities = activities,
            sites = sites,
            season = null,
            near = null,
            description = description,
            photoUrl = photoUrl,
            cellCoverage = cellCoverage,
            ratingReviews = ratingReviews,
            subcategory = subcategory,
            agency = agency,
            extras = extras,
        )

    private fun supercharger(pricebooks: List<JsonElement>): Poi.Supercharger =
        Poi.Supercharger(
            source = "tesla",
            sourceId = "charger-1",
            name = "Supercharger",
            geomGeoJson = POINT_GEO_JSON,
            region = "WA",
            country = "US",
            phone = null,
            address = null,
            infoUrl = null,
            fetchedAt = FETCHED_AT,
            lastVerified = null,
            agency = "Tesla",
            stallCount = 12,
            maxPowerKw = 250,
            facility = null,
            pricebooks = pricebooks,
        )

    private companion object {
        val FETCHED_AT: Instant = Instant.parse("2026-01-01T00:00:00Z")
        const val POINT_GEO_JSON = """{"type":"Point","coordinates":[-123.1,49.2]}"""
    }
}
