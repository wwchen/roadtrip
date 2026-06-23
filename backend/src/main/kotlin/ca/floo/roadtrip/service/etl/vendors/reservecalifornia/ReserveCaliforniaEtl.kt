package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

import ca.floo.roadtrip.models.domain.Poi
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.models.domain.ReservableType
import ca.floo.roadtrip.models.metadata.Envelope
import ca.floo.roadtrip.models.metadata.ValidationResult
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.ReservableEtlOutput
import ca.floo.roadtrip.service.etl.framework.SourceEtl
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.framework.pointGeoJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant

class ReserveCaliforniaEtl(
    override val etlSlug: String = "california-state-parks",
) : SourceEtl<ReserveCaliforniaCatalog, List<Poi.Campground>> {
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
    ): List<Poi.Campground> {
        val bucket = ctx.subcategoryFor(etlSlug)
        return dto.places.values
            .filter { it.facilityIds.isNotEmpty() }
            .map { place ->
                Poi.Campground(
                    source = etlSlug,
                    sourceId = "rc-${place.placeId}",
                    name = place.name,
                    geomGeoJson = pointGeoJson(place.longitude, place.latitude),
                    region = "CA",
                    country = "US",
                    phone = null,
                    address = null,
                    infoUrl = reserveCaliforniaParkUrl(place.placeId),
                    fetchedAt = dto.fetchedAt,
                    lastVerified = null,
                    providerRef =
                        ProviderRef.ReserveCalifornia(
                            placeId = place.placeId,
                            facilityIds = place.facilityIds,
                        ),
                    amenities = emptyList(),
                    activities = emptyList(),
                    sites = null,
                    season = null,
                    near = null,
                    description = place.description,
                    photoUrl = place.imageUrl,
                    cellCoverage = null,
                    ratingReviews = null,
                    subcategory = bucket,
                    agency = "California State Parks",
                    extras = place.raw,
                )
            }
    }
}

class ReserveCaliforniaSitesEtl(
    override val etlSlug: String = "california-state-park-sites",
) : SourceEtl<ReserveCaliforniaCatalog, ReservableEtlOutput> {
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): ReserveCaliforniaCatalog = parseCatalog(inputs.soleEnvelopes(), etlSlug)

    override fun validate(dto: ReserveCaliforniaCatalog): ValidationResult<ReserveCaliforniaCatalog> =
        if (dto.grids.isEmpty()) {
            ValidationResult.Bad(null, listOf("$etlSlug: no ReserveCalifornia grid payloads parsed"))
        } else {
            ValidationResult.Ok(dto)
        }

    override fun transform(
        dto: ReserveCaliforniaCatalog,
        ctx: TransformCtx,
    ): ReservableEtlOutput {
        val reservables = mutableListOf<ReservableRepo.Input>()
        for (grid in dto.grids.values) {
            val facility = dto.facilities[grid.facilityId]
            if (facility != null && !facility.isStandardBookable) continue
            val placeId = facility?.placeId ?: grid.placeId ?: continue
            val facilityName = facility?.name ?: grid.facilityName
            val siteType = dto.places[placeId]?.unitTypeByFacilityId?.get(grid.facilityId)
            for (unit in grid.units) {
                reservables +=
                    ReservableRepo.Input(
                        rid = ReservableId(ReservableType.SITE, VENDOR, unit.unitId.toString()),
                        name = unit.name,
                        loop = facilityName,
                        siteType = siteType,
                        raw =
                            withSynthetic(
                                unit.raw,
                                mapOf(
                                    "_parent_place_id" to placeId.toString(),
                                    "_parent_facility_id" to grid.facilityId.toString(),
                                    "_parent_facility_name" to facilityName.orEmpty(),
                                ),
                            ),
                    )
            }
        }
        return ReservableEtlOutput(reservables = reservables)
    }

    private companion object {
        const val VENDOR = "reservecalifornia"
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
    return ReserveCaliforniaPlace(
        placeId = placeId,
        name = name,
        latitude = lat,
        longitude = lon,
        facilityIds = facilityIds.distinct().sorted(),
        unitTypeByFacilityId = unitTypes,
        imageUrl = selected.stringValue("ImageUrl"),
        description = selected.stringValue("Description"),
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

private fun withSynthetic(
    raw: JsonObject,
    values: Map<String, String>,
): JsonObject =
    buildJsonObject {
        for ((k, v) in raw) put(k, v)
        for ((k, v) in values) put(k, v)
    }

private fun reserveCaliforniaParkUrl(placeId: Long): String = "https://reservecalifornia.com/park/$placeId"

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
