package ca.floo.roadtrip.model.api.poi

import ca.floo.roadtrip.model.domain.AmenityKey
import ca.floo.roadtrip.model.domain.CampgroundAlert
import ca.floo.roadtrip.model.domain.CampgroundAmenity
import ca.floo.roadtrip.model.domain.CampgroundPrice
import ca.floo.roadtrip.model.domain.CampgroundRating
import ca.floo.roadtrip.model.domain.CampgroundSchedule
import ca.floo.roadtrip.model.domain.Carrier
import ca.floo.roadtrip.model.domain.CarrierSignal
import ca.floo.roadtrip.route.common.encodeApiJson
import kotlin.test.Test
import kotlin.test.assertEquals

// The campground bags are typed on the wire and the labels are backend-owned,
// so the frontend renders `label` verbatim. These pin the exact JSON the FE
// parses: a key renamed or a label rule moved server-side shows up here first.
class PoiDetailDtoTest {
    @Test
    fun `a campground with every bag populated encodes the typed wire shape`() {
        val detail =
            PoiCategoryDetailSchema(
                parentName = "Lassen Volcanic National Park",
                amenities =
                    listOf(
                        CampgroundAmenity(key = AmenityKey.SHOWERS, present = true),
                        CampgroundAmenity(key = AmenityKey.WATER, present = false),
                        CampgroundAmenity(key = AmenityKey.TOILETS, present = true, detail = "vault"),
                        CampgroundAmenity(key = AmenityKey.OTHER, present = true, detail = "Horse corral"),
                    ).map(AmenityDto::from),
                cellCoverage =
                    listOf(
                        CarrierSignal(carrier = Carrier.VERIZON, average = 3.5, count = 12),
                        CarrierSignal(carrier = Carrier.ATT, average = 1.0),
                    ).map(CarrierSignalDto::from),
                activities = listOf("Hiking", "Fishing"),
                rating = RatingDto.from(CampgroundRating(average = 4.3, count = 87)),
                price = PriceDto.from(CampgroundPrice(minimum = 26.0, maximum = 36.0, currency = "USD")),
                schedule = ScheduleDto.from(CampgroundSchedule(checkIn = "14:00", checkOut = "11:00")),
                alerts =
                    listOf(
                        CampgroundAlert(
                            title = "Road closed",
                            body = "Highway 89 is closed north of the entrance.",
                            endsOn = "2026-10-01",
                            sourceUrl = "https://example.test/alert",
                        ),
                    ).map(AlertDto::from),
                lastVerified = "2026-06-01",
            )

        val expected = (
            """{"sources":[],"parent_name":"Lassen Volcanic National Park",""" +
                """"amenities":[""" +
                """{"key":"showers","label":"Showers","present":true},""" +
                """{"key":"water","label":"No water","present":false},""" +
                """{"key":"toilets","label":"Vault toilets","present":true,"detail":"vault"},""" +
                """{"key":"other","label":"Horse corral","present":true,"detail":"Horse corral"}],""" +
                """"cell_coverage":[""" +
                """{"carrier":"verizon","label":"Verizon","average":3.5,"count":12},""" +
                """{"carrier":"att","label":"AT&T","average":1.0}],""" +
                """"activities":["Hiking","Fishing"],""" +
                """"rating":{"average":4.3,"count":87},""" +
                """"price":{"minimum":26.0,"maximum":36.0,"currency":"USD"},""" +
                """"schedule":{"check_in":"14:00","check_out":"11:00"},""" +
                """"alerts":[{"title":"Road closed",""" +
                """"body":"Highway 89 is closed north of the entrance.",""" +
                """"ends_on":"2026-10-01","source_url":"https://example.test/alert"}],""" +
                """"last_verified":"2026-06-01","charger_amenities":[]}"""
        )
        assertEquals(expected, encodeApiJson(detail))
    }

    @Test
    fun `a campground with nothing in its bags encodes empty lists and omits the objects`() {
        assertEquals(
            """{"sources":[],"amenities":[],"cell_coverage":[],"activities":[],"alerts":[],"charger_amenities":[]}""",
            encodeApiJson(PoiCategoryDetailSchema()),
        )
    }

    @Test
    fun `a present amenity carries its own label`() {
        assertEquals("Showers", labelOf(AmenityKey.SHOWERS, present = true))
    }

    @Test
    fun `an absent amenity with a negative label carries it instead`() {
        assertEquals("No showers", labelOf(AmenityKey.SHOWERS, present = false))
    }

    @Test
    fun `an absent amenity without a negative label keeps its own label`() {
        assertEquals("Camp store", labelOf(AmenityKey.CAMP_STORE, present = false))
    }

    @Test
    fun `a toilets amenity with a kind names the kind`() {
        assertEquals("Vault toilets", labelOf(AmenityKey.TOILETS, detail = "vault"))
        assertEquals("Pit toilets", labelOf(AmenityKey.TOILETS, detail = "Pit"))
    }

    @Test
    fun `a toilets amenity without a kind keeps the plain label`() {
        assertEquals("Toilets", labelOf(AmenityKey.TOILETS))
        assertEquals("Toilets", labelOf(AmenityKey.TOILETS, detail = "  "))
    }

    @Test
    fun `an absent toilets amenity favours the negative label over the kind`() {
        assertEquals("No toilets", labelOf(AmenityKey.TOILETS, present = false, detail = "vault"))
    }

    // "No camp store" is not a fact any vendor states, so an absent amenity with
    // no negative label says nothing and must not reach the wire as a chip.
    @Test
    fun `an absent amenity without a negative label stays off the wire`() {
        assertEquals(
            """[{"key":"water","label":"No water","present":false}]""",
            encodeApiJson(
                AmenityDto.fromAll(
                    listOf(
                        CampgroundAmenity(key = AmenityKey.CAMP_STORE, present = false),
                        CampgroundAmenity(key = AmenityKey.WATER, present = false),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a blank detail is dropped rather than sent blank`() {
        assertEquals(
            """{"key":"toilets","label":"Toilets","present":true}""",
            encodeApiJson(AmenityDto.from(CampgroundAmenity(key = AmenityKey.TOILETS, present = true, detail = "  "))),
        )
    }

    @Test
    fun `an other amenity is labelled by the vendor's own words`() {
        assertEquals("Horse corral", labelOf(AmenityKey.OTHER, detail = "Horse corral"))
    }

    @Test
    fun `an other amenity with no words falls back to the key's label`() {
        assertEquals("Other", labelOf(AmenityKey.OTHER))
    }

    @Test
    fun `a carrier reading without a sample count omits it`() {
        assertEquals(
            """{"carrier":"tmobile","label":"T-Mobile","average":2.0}""",
            encodeApiJson(CarrierSignalDto.from(CarrierSignal(carrier = Carrier.TMOBILE, average = 2.0))),
        )
    }

    private fun labelOf(
        key: AmenityKey,
        present: Boolean = true,
        detail: String? = null,
    ): String = AmenityDto.from(CampgroundAmenity(key = key, present = present, detail = detail)).label
}
