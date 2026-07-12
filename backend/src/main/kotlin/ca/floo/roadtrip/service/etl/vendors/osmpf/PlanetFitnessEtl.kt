package ca.floo.roadtrip.service.etl.vendors.osmpf

import ca.floo.roadtrip.models.domain.Address
import ca.floo.roadtrip.models.domain.PlanetFitnessLocationUpsertCandidate
import ca.floo.roadtrip.models.etl.PlanetFitnessLocationEtlOutput
import ca.floo.roadtrip.models.metadata.Envelope
import ca.floo.roadtrip.models.metadata.ValidationResult
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.SourceEtl
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.time.Instant

// OSM Overpass → canonical planet_fitness_locations.
//
// Capture path: data/raw/osm-pf/<ts>.json (single envelope per run).
// Upstream payload shape:
//   { "elements": [ { "type": "node", "id": ..., "lat": ..., "lon": ...,
//                     "tags": { "name": "Planet Fitness",
//                               "addr:street": "...", "phone": "...",
//                               "opening_hours": "...", ... } },
//                   { "type": "way", "id": ..., "center": {lat, lon}, "tags": {...} },
//                   ... ] }
//
// node: lat/lon at the element. way: lat/lon under `center` (Overpass `out
// center` directive). Some entries have neither — those get dropped at
// validate.
class PlanetFitnessEtl : SourceEtl<PlanetFitnessRawDto, PlanetFitnessLocationEtlOutput> {
    override val etlSlug = "planet-fitness"

    override fun parse(inputs: InputBundle): PlanetFitnessRawDto {
        val envelope = inputs.soleEnvelopes().single()
        val payload =
            json.decodeFromJsonElement(
                PlanetFitnessRawDto.serializer(),
                envelope.payload,
            )
        return payload.copy(_fetchedAt = parseFetchedAt(envelope))
    }

    override fun validate(dto: PlanetFitnessRawDto): ValidationResult<PlanetFitnessRawDto> {
        // The DTO can hold a 200-elements payload; we validate per-element
        // at transform time and drop invalid elements there. This stage
        // only checks the outer shape.
        val errors = mutableListOf<String>()
        if (dto.elements.isEmpty()) errors += "no elements in payload"
        return if (errors.isEmpty()) {
            ValidationResult.Ok(dto)
        } else {
            ValidationResult.Bad(sourceId = null, errors = errors)
        }
    }

    override fun transform(
        dto: PlanetFitnessRawDto,
        ctx: TransformCtx,
    ): PlanetFitnessLocationEtlOutput =
        PlanetFitnessLocationEtlOutput(
            locations = dto.elements.mapNotNull { el -> transformElement(el) },
        )

    private fun transformElement(el: OverpassElement): PlanetFitnessLocationUpsertCandidate? {
        // Resolve lat/lon: nodes have it directly, ways/relations have it
        // under `center` (Overpass `out center` semantics).
        val lat = el.lat ?: el.center?.lat ?: return null
        val lon = el.lon ?: el.center?.lon ?: return null
        val tags = el.tags ?: emptyMap()
        // OSM source_id format: <type>-<id>. Lowercased, hyphenated to
        // satisfy the V5 source_id CHECK constraint (^[a-z0-9:_-]+$).
        val sourceId = "${el.type}-${el.id}"

        // Address bag. Empty values dropped so the row carries null
        // when nothing's known instead of {"street":"","city":""}.
        val address = buildAddress(tags)

        return PlanetFitnessLocationUpsertCandidate(
            locationId = sourceId,
            name = tags["name"] ?: "Planet Fitness",
            latitude = lat,
            longitude = lon,
            region = tags["addr:state"]?.takeIf { it.isNotBlank() },
            country = "US", // OSM-PF poller's bbox is continental US; safe default
            phone = tags["phone"]?.takeIf { it.isNotBlank() },
            address = addressJson(address),
            infoUrl = tags["website"]?.takeIf { it.isNotBlank() },
            // OSM stores the interesting per-element data as tags (key/value
            // strings). Surface the full tag map via extras so the drawer's
            // "Upstream data" accordion has all of it.
            payload = elementExtras(el),
        )
    }

    private fun elementExtras(el: OverpassElement): JsonElement =
        extrasJson.encodeToJsonElement(
            OverpassElementExtrasDto(
                type = el.type,
                id = el.id,
                lat = el.lat,
                lon = el.lon,
                center = el.center,
                tags = el.tags,
            ),
        )

    private fun buildAddress(tags: Map<String, String>): Address? {
        val street =
            listOfNotNull(tags["addr:housenumber"], tags["addr:street"])
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
        val city = tags["addr:city"]?.takeIf { it.isNotBlank() }
        val state = tags["addr:state"]?.takeIf { it.isNotBlank() }
        val postcode = tags["addr:postcode"]?.takeIf { it.isNotBlank() }
        if (street == null && city == null && state == null && postcode == null) return null
        return Address(street = street, city = city, state = state, postcode = postcode, country = "US")
    }

    private fun addressJson(address: Address?): JsonElement? {
        address ?: return null
        return buildJsonObject {
            address.street?.let { put("street", it) }
            address.city?.let { put("city", it) }
            address.state?.let { put("state", it) }
            address.postcode?.let { put("postcode", it) }
            address.country?.let { put("country", it) }
        }
    }

    private fun parseFetchedAt(envelope: Envelope): Instant =
        try {
            Instant.parse(envelope.fetchedAt)
        } catch (e: Exception) {
            Instant.now()
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @OptIn(ExperimentalSerializationApi::class)
        private val extrasJson =
            Json {
                encodeDefaults = false
                explicitNulls = false
            }
    }
}

@Serializable
private data class OverpassElementExtrasDto(
    val type: String,
    val id: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val center: OverpassCenter? = null,
    val tags: Map<String, String>? = null,
)
