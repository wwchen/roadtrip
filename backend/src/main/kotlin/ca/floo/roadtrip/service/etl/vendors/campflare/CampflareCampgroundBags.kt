package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.domain.AmenityKey
import ca.floo.roadtrip.model.domain.CampgroundAlert
import ca.floo.roadtrip.model.domain.CampgroundAmenity
import ca.floo.roadtrip.model.domain.CampgroundMetadata
import ca.floo.roadtrip.model.domain.CampgroundPrice
import ca.floo.roadtrip.model.domain.CampgroundSchedule
import ca.floo.roadtrip.model.domain.Carrier
import ca.floo.roadtrip.model.domain.CarrierSignal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Campflare's dump bags → the typed campground vocabularies. Pure functions of
 * the captured JSON; `V57__typed_campground_bags.sql` canonicalizes the same
 * shapes for rows written before the ETL did.
 */
object CampflareCampgroundBags {
    private const val TOILET_KIND_KEY = "toilet_kind"
    private const val PRICE_MINIMUM_KEY = "minimum"
    private const val PRICE_MAXIMUM_KEY = "maximum"
    private const val PRICE_CURRENCY_KEY = "currency"
    private const val PRICE_CURRENCY_CODE_KEY = "currency_code"
    private const val CHECK_IN_TIME_KEY = "check_in_time"
    private const val CHECK_OUT_TIME_KEY = "check_out_time"
    private const val ALERT_TITLE_KEY = "title"
    private const val ALERT_BODY_KEY = "content"
    private const val ALERT_END_DATE_KEY = "end_date"
    private const val ALERT_SOURCE_URL_KEY = "source_url"
    private const val LAST_UPDATED_KEY = "last_updated"

    /**
     * Upstream ships an object of canonical keys with `true` / `false` / JSON
     * `null`, plus a `toilet_kind` string. A null value means "unknown", so it
     * is omitted rather than stored as absent.
     */
    fun amenities(raw: JsonObject): List<CampgroundAmenity> {
        val entries = mutableListOf<CampgroundAmenity>()
        for ((key, value) in raw) {
            if (key == TOILET_KIND_KEY || value is JsonNull) continue
            val amenityKey = AmenityKey.fromWire(key)
            entries +=
                if (amenityKey == null) {
                    CampgroundAmenity(AmenityKey.OTHER, present = true, detail = key)
                } else {
                    CampgroundAmenity(
                        amenityKey,
                        present = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: true,
                    )
                }
        }
        return withToiletKind(entries, raw.stringField(TOILET_KIND_KEY))
    }

    /** Campflare rates a carrier with a bare number and no sample count. */
    fun carriers(raw: JsonObject): List<CarrierSignal> =
        raw.mapNotNull { (key, value) ->
            val carrier = Carrier.fromWire(key) ?: return@mapNotNull null
            val average = (value as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            CarrierSignal(carrier = carrier, average = average, count = null)
        }

    fun price(raw: JsonObject?): CampgroundPrice? {
        if (raw == null) return null
        val parsed =
            CampgroundPrice(
                minimum = raw.doubleField(PRICE_MINIMUM_KEY),
                maximum = raw.doubleField(PRICE_MAXIMUM_KEY),
                currency = raw.stringField(PRICE_CURRENCY_CODE_KEY) ?: raw.stringField(PRICE_CURRENCY_KEY),
            )
        return parsed.takeIf { it != CampgroundPrice() }
    }

    fun schedule(raw: JsonObject?): CampgroundSchedule? {
        if (raw == null) return null
        val parsed =
            CampgroundSchedule(
                checkIn = raw.stringField(CHECK_IN_TIME_KEY),
                checkOut = raw.stringField(CHECK_OUT_TIME_KEY),
            )
        return parsed.takeIf { it != CampgroundSchedule() }
    }

    /** An alert with no body has nothing to show, so it is dropped. */
    fun alerts(raw: JsonArray?): List<CampgroundAlert> =
        raw.orEmpty().mapNotNull { entry ->
            val alert = entry as? JsonObject ?: return@mapNotNull null
            val body = alert.stringField(ALERT_BODY_KEY) ?: return@mapNotNull null
            CampgroundAlert(
                title = alert.stringField(ALERT_TITLE_KEY),
                body = body,
                endsOn = alert.stringField(ALERT_END_DATE_KEY),
                sourceUrl = alert.stringField(ALERT_SOURCE_URL_KEY),
            )
        }

    /** The `has_*` capability flags upstream also ships have no reader; only the timestamp survives. */
    fun metadata(raw: JsonObject?): CampgroundMetadata? {
        if (raw == null) return null
        val parsed = CampgroundMetadata(lastUpdated = raw.stringField(LAST_UPDATED_KEY))
        return parsed.takeIf { it != CampgroundMetadata() }
    }

    /** `toilet_kind` is a detail of the toilets amenity, and stands in for it when the flag is absent. */
    private fun withToiletKind(
        entries: List<CampgroundAmenity>,
        toiletKind: String?,
    ): List<CampgroundAmenity> {
        if (toiletKind == null) return entries
        if (entries.none { it.key == AmenityKey.TOILETS }) {
            return listOf(CampgroundAmenity(AmenityKey.TOILETS, present = true, detail = toiletKind)) + entries
        }
        return entries.map { entry ->
            if (entry.key == AmenityKey.TOILETS) entry.copy(detail = toiletKind) else entry
        }
    }
}
