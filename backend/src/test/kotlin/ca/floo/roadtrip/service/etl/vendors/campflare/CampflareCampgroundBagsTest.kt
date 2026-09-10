package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.domain.AmenityKey
import ca.floo.roadtrip.model.domain.CampgroundAlert
import ca.floo.roadtrip.model.domain.CampgroundAmenity
import ca.floo.roadtrip.model.domain.CampgroundMetadata
import ca.floo.roadtrip.model.domain.CampgroundPrice
import ca.floo.roadtrip.model.domain.CampgroundSchedule
import ca.floo.roadtrip.model.domain.Carrier
import ca.floo.roadtrip.model.domain.CarrierSignal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CampflareCampgroundBagsTest {
    @Test
    fun `amenity keys map to the vocabulary and json nulls drop out`() {
        val amenities =
            CampflareCampgroundBags.amenities(
                obj("""{"toilets": true, "water": false, "wifi": null, "showers": true}"""),
            )

        assertEquals(
            listOf(
                CampgroundAmenity(AmenityKey.TOILETS, present = true),
                CampgroundAmenity(AmenityKey.WATER, present = false),
                CampgroundAmenity(AmenityKey.SHOWERS, present = true),
            ),
            amenities,
        )
    }

    @Test
    fun `toilet kind rides as detail on the toilets amenity`() {
        val amenities = CampflareCampgroundBags.amenities(obj("""{"toilets": true, "toilet_kind": "vault"}"""))

        assertEquals(listOf(CampgroundAmenity(AmenityKey.TOILETS, present = true, detail = "vault")), amenities)
    }

    @Test
    fun `toilet kind creates the toilets amenity when the boolean is json null`() {
        val amenities =
            CampflareCampgroundBags.amenities(
                obj("""{"toilets": null, "toilet_kind": "vault", "water": true}"""),
            )

        assertEquals(
            listOf(
                CampgroundAmenity(AmenityKey.TOILETS, present = true, detail = "vault"),
                CampgroundAmenity(AmenityKey.WATER, present = true),
            ),
            amenities,
        )
    }

    @Test
    fun `an unknown amenity key lands as other carrying the key as detail`() {
        val amenities = CampflareCampgroundBags.amenities(obj("""{"kayak_launch": true, "boat_ramp": null}"""))

        assertEquals(listOf(CampgroundAmenity(AmenityKey.OTHER, present = true, detail = "kayak_launch")), amenities)
    }

    @Test
    fun `a literal other key still lands as other carrying the key as detail`() {
        val amenities = CampflareCampgroundBags.amenities(obj("""{"other": true}"""))

        assertEquals(listOf(CampgroundAmenity(AmenityKey.OTHER, present = true, detail = "other")), amenities)
    }

    @Test
    fun `a json string value is present even when it spells out false`() {
        val amenities = CampflareCampgroundBags.amenities(obj("""{"wifi": "false"}"""))

        assertEquals(listOf(CampgroundAmenity(AmenityKey.WIFI, present = true)), amenities)
    }

    @Test
    fun `bare number carriers become countless signals and unknown carriers drop`() {
        val carriers =
            CampflareCampgroundBags.carriers(
                obj("""{"verizon": 0.6, "uscell": 2.5, "tmobile": 1.8, "boost": 1.0, "att": null}"""),
            )

        assertEquals(
            listOf(
                CarrierSignal(Carrier.VERIZON, average = 0.6, count = null),
                CarrierSignal(Carrier.US_CELLULAR, average = 2.5, count = null),
                CarrierSignal(Carrier.TMOBILE, average = 1.8, count = null),
            ),
            carriers,
        )
    }

    @Test
    fun `price prefers the currency code over the spelled-out currency`() {
        val price =
            CampflareCampgroundBags.price(
                obj("""{"minimum": 36, "maximum": 45, "currency": "US Dollar", "currency_code": "USD"}"""),
            )

        assertEquals(CampgroundPrice(minimum = 36.0, maximum = 45.0, currency = "USD"), price)
        assertEquals(
            CampgroundPrice(currency = "CAD"),
            CampflareCampgroundBags.price(obj("""{"currency": "CAD"}""")),
        )
        assertNull(CampflareCampgroundBags.price(null))
        assertNull(CampflareCampgroundBags.price(obj("""{"uniform": true}""")))
    }

    @Test
    fun `schedule reads the upstream time keys and drops uniform`() {
        val schedule =
            CampflareCampgroundBags.schedule(
                obj("""{"check_in_time": "14:00", "check_out_time": "12:00", "uniform": true}"""),
            )

        assertEquals(CampgroundSchedule(checkIn = "14:00", checkOut = "12:00"), schedule)
        assertNull(CampflareCampgroundBags.schedule(obj("""{"uniform": true}""")))
        assertNull(CampflareCampgroundBags.schedule(null))
    }

    @Test
    fun `alerts read the upstream content keys and skip bodyless entries`() {
        val alerts =
            CampflareCampgroundBags.alerts(
                arr(
                    """
                    [
                      {
                        "title": "Fire ban",
                        "content": "No open flames.",
                        "end_date": "2026-09-01",
                        "source_url": "https://example.test/alert"
                      },
                      {"title": "No body here"}
                    ]
                    """.trimIndent(),
                ),
            )

        assertEquals(
            listOf(
                CampgroundAlert(
                    title = "Fire ban",
                    body = "No open flames.",
                    endsOn = "2026-09-01",
                    sourceUrl = "https://example.test/alert",
                ),
            ),
            alerts,
        )
        assertEquals(emptyList(), CampflareCampgroundBags.alerts(null))
    }

    @Test
    fun `metadata keeps last updated and drops the has flags`() {
        val metadata =
            CampflareCampgroundBags.metadata(
                obj("""{"last_updated": "2026-07-01T00:00:00Z", "has_availability_data": true}"""),
            )

        assertEquals(CampgroundMetadata(lastUpdated = "2026-07-01T00:00:00Z"), metadata)
        assertNull(CampflareCampgroundBags.metadata(obj("""{"has_availability_data": true}""")))
        assertNull(CampflareCampgroundBags.metadata(null))
    }

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun arr(raw: String): JsonArray = Json.parseToJsonElement(raw).jsonArray
}
