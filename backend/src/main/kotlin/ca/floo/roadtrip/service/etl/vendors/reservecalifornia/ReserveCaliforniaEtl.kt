package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

import ca.floo.roadtrip.models.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.models.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.models.domain.DEFAULT_CAMPSITE_KIND
import ca.floo.roadtrip.models.metadata.Envelope
import ca.floo.roadtrip.models.metadata.ValidationResult
import ca.floo.roadtrip.service.etl.framework.CampgroundEtlOutput
import ca.floo.roadtrip.service.etl.framework.CampsiteEtlOutput
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.SourceEtl
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant

class ReserveCaliforniaEtl(
    override val etlSlug: String = "california-state-parks",
) : SourceEtl<ReserveCaliforniaCatalog, CampgroundEtlOutput> {
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): ReserveCaliforniaCatalog = parseCatalog(inputs.soleEnvelopes(), etlSlug)

    override fun validate(dto: ReserveCaliforniaCatalog): ValidationResult<ReserveCaliforniaCatalog> =
        if (dto.places.values.none { it.facilityIds.isNotEmpty() }) {
            ValidationResult.Bad(null, listOf("$etlSlug: no ReserveCalifornia place payloads with facilities parsed"))
        } else {
            ValidationResult.Ok(dto)
        }

    override fun transform(
        dto: ReserveCaliforniaCatalog,
        ctx: TransformCtx,
    ): CampgroundEtlOutput {
        val bucket = ctx.subcategoryFor(etlSlug)
        val agency = ctx.requiredConstantAgency(etlSlug)
        return CampgroundEtlOutput(
            campgrounds =
                dto.places.values
                    .filter { it.facilityIds.isNotEmpty() }
                    .map { place ->
                        val parkUrl = reserveCaliforniaParkUrl(place.placeId)
                        CampgroundUpsertCandidate(
                            vendor = etlSlug,
                            vendorRefId = "$CAMPGROUND_REF_PREFIX${place.placeId}",
                            name = place.name,
                            latitude = place.latitude,
                            longitude = place.longitude,
                            kind = bucket,
                            mediumDescription = place.description,
                            location = locationPayload(place.latitude, place.longitude),
                            amenities = amenitiesPayload(place.amenities),
                            reservationUrl = parkUrl,
                            links = linksPayload(parkUrl),
                            photos = place.imageUrl?.let(::photoPayload),
                            management = managementPayload(agency),
                            metadata = metadataPayload(place),
                            sourceUrl = parkUrl,
                            sourcePayload = place.raw,
                            vendorRefPayload = providerRefPayload(place),
                        )
                    },
        )
    }
}

class ReserveCaliforniaSitesEtl(
    override val etlSlug: String = "california-state-park-sites",
) : SourceEtl<ReserveCaliforniaCatalog, CampsiteEtlOutput> {
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): ReserveCaliforniaCatalog = parseCatalog(inputs.soleEnvelopes(), etlSlug)

    override fun validate(dto: ReserveCaliforniaCatalog): ValidationResult<ReserveCaliforniaCatalog> =
        if (dto.grids.values.none { it.units.isNotEmpty() }) {
            ValidationResult.Bad(null, listOf("$etlSlug: no ReserveCalifornia grid payloads with units parsed"))
        } else {
            ValidationResult.Ok(dto)
        }

    override fun transform(
        dto: ReserveCaliforniaCatalog,
        ctx: TransformCtx,
    ): CampsiteEtlOutput =
        CampsiteEtlOutput(
            campsites =
                dto.grids.values.flatMap { grid ->
                    val facility = dto.facilities[grid.facilityId]
                    if (facility?.isStandardBookable == false) return@flatMap emptyList()
                    val placeId = grid.placeId ?: facility?.placeId ?: return@flatMap emptyList()
                    val place = dto.places[placeId] ?: return@flatMap emptyList()
                    if (grid.facilityId !in place.facilityIds) return@flatMap emptyList()
                    val kind = place.unitTypeByFacilityId[grid.facilityId] ?: DEFAULT_CAMPSITE_KIND
                    grid.units.map { unit ->
                        CampsiteUpsertCandidate(
                            vendor = RESERVECALIFORNIA_VENDOR,
                            vendorRefId = unit.unitId.toString(),
                            parentVendor = PARENT_CAMPGROUND_VENDOR,
                            parentVendorRefId = "$CAMPGROUND_REF_PREFIX$placeId",
                            name = unit.name?.takeIf { it.isNotBlank() } ?: unit.unitId.toString(),
                            kind = kind,
                            loopName = grid.facilityName ?: facility?.name,
                            reservationUrl = reserveCaliforniaParkUrl(placeId),
                            kindListed = kind,
                            sourcePayload = campsiteSourcePayload(unit, grid, placeId, facility),
                            vendorRefPayload = campsiteProviderRefPayload(unit, grid, placeId),
                        )
                    }
                },
        )
}

private fun locationPayload(
    latitude: Double,
    longitude: Double,
): JsonObject =
    buildJsonObject {
        put("latitude", latitude)
        put("longitude", longitude)
        put("region", REGION)
        put("country", COUNTRY)
    }

private fun linksPayload(url: String): JsonElement =
    buildJsonArray {
        add(
            buildJsonObject {
                put("url", url)
            },
        )
    }

private fun photoPayload(url: String): JsonElement =
    buildJsonArray {
        add(
            buildJsonObject {
                put("url", url)
            },
        )
    }

private fun managementPayload(agency: String): JsonObject =
    buildJsonObject {
        put("agency", agency)
    }

private fun amenitiesPayload(values: List<String>): JsonObject? {
    if (values.isEmpty()) return null
    return buildJsonObject {
        values.forEach { put(it, true) }
    }
}

private fun metadataPayload(place: ReserveCaliforniaPlace): JsonObject? {
    val payload =
        buildJsonObject {
            if (place.activities.isNotEmpty()) {
                stringArrayPayload(place.activities)?.let { put("activities", it) }
            }
            put("facility_unit_types", facilityUnitTypePayload(place))
        }
    return payload.takeIf { it.isNotEmpty() }
}

private fun providerRefPayload(place: ReserveCaliforniaPlace): JsonObject =
    buildJsonObject {
        put("place_id", place.placeId)
        put(
            "facility_ids",
            buildJsonArray {
                place.facilityIds.forEach { add(it) }
            },
        )
    }

private fun campsiteProviderRefPayload(
    unit: ReserveCaliforniaUnit,
    grid: ReserveCaliforniaGridCatalog,
    placeId: Long,
): JsonObject =
    buildJsonObject {
        put("unit_id", unit.unitId)
        put("facility_id", grid.facilityId)
        put(PARENT_PLACE_ID_KEY, placeId)
        put("place_id", placeId)
    }

private fun campsiteSourcePayload(
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

private fun stringArrayPayload(values: List<String>): JsonElement? {
    if (values.isEmpty()) return null
    return buildJsonArray {
        values.forEach { add(it) }
    }
}

private fun facilityUnitTypePayload(place: ReserveCaliforniaPlace): JsonObject =
    buildJsonObject {
        for ((facilityId, unitType) in place.unitTypeByFacilityId) {
            put(facilityId.toString(), unitType)
        }
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
        fetchedAt = parseFetchedAt(envelopes.first()),
    )
}

private fun parsePlace(payload: JsonObject): ReserveCaliforniaPlace? {
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

private fun parseFacility(payload: JsonObject): ReserveCaliforniaFacility? {
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

private fun parseGrid(payload: JsonObject): ReserveCaliforniaGridCatalog? {
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

private fun parseFetchedAt(envelope: Envelope): Instant = runCatching { Instant.parse(envelope.fetchedAt) }.getOrDefault(Instant.now())

private fun JsonObject.stringValue(key: String): String? =
    this[key]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun parseHighlights(raw: String?): List<String> =
    raw
        ?.split(Regex("""(?i)<br\s*/?>"""))
        ?.map { it.replace(Regex("""<[^>]+>"""), " ") }
        ?.map { it.replace(Regex("""\s+"""), " ").trim() }
        ?.filter { it.isNotEmpty() }
        ?.distinct()
        .orEmpty()

private fun isActivityHighlight(label: String): Boolean {
    val normalized = label.lowercase()
    return ACTIVITY_HINTS.any { normalized.contains(it) }
}

private fun reserveCaliforniaParkUrl(placeId: Long): String = "https://reservecalifornia.com/park/$placeId"

private const val RESERVECALIFORNIA_VENDOR = "reservecalifornia"
private const val PARENT_CAMPGROUND_VENDOR = "california-state-parks"
private const val CAMPGROUND_REF_PREFIX = "rc-"
private const val REGION = "CA"
private const val COUNTRY = "US"
private const val PARENT_PLACE_ID_KEY = "_parent_place_id"

data class ReserveCaliforniaCatalog(
    val places: Map<Long, ReserveCaliforniaPlace>,
    val facilities: Map<Long, ReserveCaliforniaFacility>,
    val grids: Map<Long, ReserveCaliforniaGridCatalog>,
    val fetchedAt: Instant,
)

data class ReserveCaliforniaPlace(
    val placeId: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val facilityIds: List<Long>,
    val unitTypeByFacilityId: Map<Long, String>,
    val imageUrl: String?,
    val description: String?,
    val amenities: List<String>,
    val activities: List<String>,
    val raw: JsonElement,
)

data class ReserveCaliforniaFacility(
    val facilityId: Long,
    val placeId: Long?,
    val name: String?,
    val facilityTypeNew: Long?,
    val facilityBehaviourType: Long?,
    val allowWebBooking: Boolean?,
    val raw: JsonElement,
) {
    val isStandardBookable: Boolean
        get() = facilityTypeNew != 2L && facilityBehaviourType != 2L && allowWebBooking != false
}

data class ReserveCaliforniaGridCatalog(
    val facilityId: Long,
    val placeId: Long?,
    val facilityName: String?,
    val units: List<ReserveCaliforniaUnit>,
)

data class ReserveCaliforniaUnit(
    val unitId: Long,
    val name: String?,
    val raw: JsonObject,
)

private val ACTIVITY_HINTS =
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
