package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

import ca.floo.roadtrip.model.domain.AmenityKey
import ca.floo.roadtrip.model.domain.CampgroundAmenity
import ca.floo.roadtrip.model.domain.CampgroundLink
import ca.floo.roadtrip.model.domain.CampgroundLocation
import ca.floo.roadtrip.model.domain.CampgroundManagement
import ca.floo.roadtrip.model.domain.CampgroundMetadata
import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.CatalogPhoto
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.TransformResult
import ca.floo.roadtrip.service.etl.framework.CampgroundEtl
import ca.floo.roadtrip.service.etl.framework.HtmlText
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.framework.fetchedAtOrNow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class ReserveCaliforniaCampgroundsEtl(
    override val etlSlug: String = "reservecalifornia-campgrounds",
) : CampgroundEtl<ReserveCaliforniaCatalog> {
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): Sequence<ParseResult<ReserveCaliforniaCatalog>> =
        sequence {
            val catalog = parseCatalog(inputs.soleEnvelopes(), etlSlug)
            if (catalog.places.values.none { it.facilityIds.isNotEmpty() }) {
                yield(ParseResult.Bad(null, listOf("$etlSlug: no ReserveCalifornia place payloads with facilities parsed")))
            } else {
                yield(ParseResult.Ok(catalog))
            }
        }

    override fun transform(
        dto: ReserveCaliforniaCatalog,
        ctx: TransformCtx,
    ): Sequence<TransformResult<CampgroundUpsertCandidate>> =
        sequence {
            val bucket = ctx.subcategoryFor(etlSlug)
            val agency = ctx.requiredConstantAgency(etlSlug)
            for (place in dto.places.values) {
                if (place.facilityIds.isEmpty()) {
                    yield(TransformResult.Bad(place.placeId.toString(), listOf("place has no facility ids")))
                    continue
                }
                val parkUrl = reserveCaliforniaParkUrl(place.placeId)
                yield(
                    TransformResult.Ok(
                        CampgroundUpsertCandidate(
                            dataProviderRef = DataProviderRef.ReserveCalifornia(id = place.placeId.toString()),
                            bookingProvider = BookingProvider.RESERVECALIFORNIA,
                            bookingProviderRef = "${place.placeId}:${place.facilityIds.joinToString(",")}",
                            name = place.name,
                            latitude = place.latitude,
                            longitude = place.longitude,
                            kind = bucket,
                            mediumDescription = place.description,
                            location = CampgroundLocation(place.latitude, place.longitude, region = REGION, country = COUNTRY),
                            amenities = highlightAmenities(place.amenities),
                            reservationUrl = parkUrl,
                            links = listOf(CampgroundLink(parkUrl)),
                            photos = listOfNotNull(place.imageUrl?.let(::CatalogPhoto)),
                            management = CampgroundManagement(agency),
                            metadata = placeMetadata(place),
                            sourceUrl = parkUrl,
                            sourcePayload = place.raw,
                        ),
                    ),
                )
            }
        }
}

/**
 * ReserveCalifornia publishes free-text highlight labels, not a vocabulary. The
 * ones that name a known amenity are mapped; the rest ride as [AmenityKey.OTHER]
 * carrying the vendor's own words. Two labels can name the same amenity
 * ("Restrooms", "Comfort Station"), so the result is de-duplicated.
 */
internal fun highlightAmenities(labels: List<String>): List<CampgroundAmenity> =
    labels
        .mapNotNull { raw ->
            val label = raw.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val key = amenityKeyByHighlight[label.lowercase()]
            if (key == null) {
                CampgroundAmenity(AmenityKey.OTHER, detail = label)
            } else {
                CampgroundAmenity(key)
            }
        }.distinctBy { it.key to it.detail }

private fun placeMetadata(place: ReserveCaliforniaPlace): CampgroundMetadata? =
    CampgroundMetadata(activities = place.activities).takeIf { it != CampgroundMetadata() }

internal fun campsiteSourcePayload(
    unit: ReserveCaliforniaUnit,
    grid: ReserveCaliforniaGridCatalog,
    placeId: Long,
    facility: ReserveCaliforniaFacility?,
): JsonObject =
    buildJsonObject {
        for ((key, value) in unit.raw) put(key, value)
        put("unit_id", unit.unitId)
        put("facility_id", grid.facilityId)
        put(PARENT_PLACE_ID_KEY, placeId)
        grid.facilityName?.let { put("facility_name", it) }
        facility?.raw?.let { put("facility", it) }
    }

internal fun parseCatalog(
    envelopes: List<Envelope>,
    label: String,
): ReserveCaliforniaCatalog {
    require(envelopes.isNotEmpty()) { "$label: no envelopes captured (run fetch_reservecalifornia.py first)" }
    val places = linkedMapOf<Long, ReserveCaliforniaPlace>()
    val facilities = linkedMapOf<Long, ReserveCaliforniaFacility>()
    val grids = linkedMapOf<Long, ReserveCaliforniaGridCatalog>()

    for (envelope in envelopes) {
        val payload = envelope.payload as? JsonObject ?: continue
        when {
            envelope.part?.startsWith("place-") == true -> {
                parsePlace(payload)?.let { places[it.placeId] = it }
            }
            envelope.part?.startsWith("facility-") == true -> {
                parseFacility(payload)?.let { facilities[it.facilityId] = it }
            }
            envelope.part?.startsWith("grid-") == true || payload["Facility"] != null -> {
                parseGrid(payload)?.let { grids[it.facilityId] = it }
            }
        }
    }
    return ReserveCaliforniaCatalog(
        places = places,
        facilities = facilities,
        grids = grids,
        fetchedAt = envelopes.first().fetchedAtOrNow(),
    )
}

internal fun parsePlace(payload: JsonObject): ReserveCaliforniaPlace? {
    val selected = payload["SelectedPlace"]?.jsonObject ?: return null
    val placeId = selected["PlaceId"]?.jsonPrimitive?.longOrNull ?: return null
    val name = selected["Name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val lat = selected["Latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
    val lon = selected["Longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
    val facilities = selected["Facilities"]?.jsonObject ?: JsonObject(emptyMap())
    val facilityIds = mutableListOf<Long>()
    val unitTypes = linkedMapOf<Long, String>()
    for ((key, rawFacility) in facilities) {
        val facility = rawFacility as? JsonObject ?: continue
        val facilityId = facility["FacilityId"]?.jsonPrimitive?.longOrNull ?: key.toLongOrNull() ?: continue
        facilityIds += facilityId
        val unitTypeName =
            facility["UnitTypes"]
                ?.jsonObject
                ?.values
                ?.asSequence()
                ?.mapNotNull { (it as? JsonObject)?.get("Name")?.jsonPrimitive?.contentOrNull }
                ?.firstOrNull()
        if (unitTypeName != null) unitTypes[facilityId] = unitTypeName
    }
    val highlights = parseHighlights(selected["Allhighlights"]?.jsonPrimitive?.contentOrNull)
    return ReserveCaliforniaPlace(
        placeId = placeId,
        name = name,
        latitude = lat,
        longitude = lon,
        facilityIds = facilityIds.distinct().sorted(),
        unitTypeByFacilityId = unitTypes,
        imageUrl = selected.stringValue("ImageUrl"),
        description = selected.stringValue("Description"),
        amenities = highlights.filterNot(::isActivityHighlight),
        activities = highlights.filter(::isActivityHighlight),
        raw = selected,
    )
}

internal fun parseFacility(payload: JsonObject): ReserveCaliforniaFacility? {
    val facilityId = payload["FacilityId"]?.jsonPrimitive?.longOrNull ?: return null
    return ReserveCaliforniaFacility(
        facilityId = facilityId,
        placeId = payload["PlaceId"]?.jsonPrimitive?.longOrNull,
        name = payload["Name"]?.jsonPrimitive?.contentOrNull,
        facilityTypeNew = payload["FacilityTypeNew"]?.jsonPrimitive?.longOrNull,
        facilityBehaviourType = payload["FacilityBehaviourType"]?.jsonPrimitive?.longOrNull,
        allowWebBooking = payload["AllowWebBooking"]?.jsonPrimitive?.booleanOrNull,
        raw = payload,
    )
}

internal fun parseGrid(payload: JsonObject): ReserveCaliforniaGridCatalog? {
    val facility = payload["Facility"]?.jsonObject ?: return null
    val facilityId = facility["FacilityId"]?.jsonPrimitive?.longOrNull ?: return null
    val units = facility["Units"]?.jsonObject ?: JsonObject(emptyMap())
    return ReserveCaliforniaGridCatalog(
        facilityId = facilityId,
        placeId = facility["PlaceId"]?.jsonPrimitive?.longOrNull,
        facilityName = facility["Name"]?.jsonPrimitive?.contentOrNull,
        units =
            units.values.mapNotNull { raw ->
                val unit = raw as? JsonObject ?: return@mapNotNull null
                val unitId = unit["UnitId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                ReserveCaliforniaUnit(
                    unitId = unitId,
                    name = unit["Name"]?.jsonPrimitive?.contentOrNull,
                    raw = unit,
                )
            },
    )
}

internal fun JsonObject.stringValue(key: String): String? =
    this[key]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

internal fun parseHighlights(raw: String?): List<String> =
    raw
        ?.split(Regex("""(?i)<br\s*/?>"""))
        ?.map(HtmlText::stripTags)
        ?.filter { it.isNotEmpty() }
        ?.distinct()
        .orEmpty()

internal fun isActivityHighlight(label: String): Boolean {
    val normalized = label.lowercase()
    return activityHints.any { normalized.contains(it) }
}

internal fun reserveCaliforniaParkUrl(placeId: Long): String = "https://reservecalifornia.com/park/$placeId"

internal const val REGION = "CA"
internal const val COUNTRY = "US"
internal const val PARENT_PLACE_ID_KEY = "_parent_place_id"

// Highlight label (lowercased, trimmed) → the amenity it names. Everything else is OTHER.
private val amenityKeyByHighlight =
    mapOf(
        "restrooms" to AmenityKey.TOILETS,
        "toilet, accessible" to AmenityKey.TOILETS,
        "comfort station" to AmenityKey.TOILETS,
        "showers" to AmenityKey.SHOWERS,
        "rinse showers" to AmenityKey.SHOWERS,
        "dump station" to AmenityKey.DUMP_STATION,
        "store-convenience" to AmenityKey.CAMP_STORE,
        "store - convenience" to AmenityKey.CAMP_STORE,
        "fire rings" to AmenityKey.FIRES_ALLOWED,
    )

internal val activityHints =
    setOf(
        "biking",
        "bird",
        "boating",
        "fishing",
        "hiking",
        "riding",
        "ski",
        "swimming",
        "trail",
        "water sport",
    )
