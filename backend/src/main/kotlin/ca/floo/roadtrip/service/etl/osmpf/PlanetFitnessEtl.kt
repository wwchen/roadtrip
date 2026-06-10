package ca.floo.roadtrip.service.etl.osmpf

import ca.floo.roadtrip.models.Address
import ca.floo.roadtrip.models.Envelope
import ca.floo.roadtrip.models.Poi
import ca.floo.roadtrip.models.ValidationResult
import ca.floo.roadtrip.service.etl.InputBundle
import ca.floo.roadtrip.service.etl.SourceEtl
import ca.floo.roadtrip.service.etl.TransformCtx
import ca.floo.roadtrip.service.etl.pointGeoJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

// OSM Overpass → Poi.PlanetFitness.
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
class PlanetFitnessEtl : SourceEtl<PlanetFitnessRawDto, List<Poi.PlanetFitness>> {
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
    ): List<Poi.PlanetFitness> = dto.elements.mapNotNull { el -> transformElement(el, dto._fetchedAt) }

    private fun transformElement(
        el: OverpassElement,
        fetchedAt: Instant,
    ): Poi.PlanetFitness? {
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

        return Poi.PlanetFitness(
            source = etlSlug,
            sourceId = sourceId,
            name = tags["name"] ?: "Planet Fitness",
            geomGeoJson = pointGeoJson(lon, lat),
            region = tags["addr:state"]?.takeIf { it.isNotBlank() },
            country = "US", // OSM-PF poller's bbox is continental US; safe default
            phone = tags["phone"]?.takeIf { it.isNotBlank() },
            address = address,
            infoUrl = tags["website"]?.takeIf { it.isNotBlank() },
            fetchedAt = fetchedAt,
            lastVerified = null, // OSM — no editorial-touch field
            openingHours = tags["opening_hours"]?.takeIf { it.isNotBlank() },
            // OSM stores the interesting per-element data as tags (key/value
            // strings). Surface the full tag map via extras so the drawer's
            // "Upstream data" accordion has all of it.
            extras = elementExtras(el),
        )
    }

    private fun elementExtras(el: OverpassElement): JsonElement =
        buildJsonObject {
            put("type", JsonPrimitive(el.type))
            put("id", JsonPrimitive(el.id))
            el.lat?.let { put("lat", JsonPrimitive(it)) }
            el.lon?.let { put("lon", JsonPrimitive(it)) }
            el.center?.let {
                put(
                    "center",
                    buildJsonObject {
                        put("lat", JsonPrimitive(it.lat))
                        put("lon", JsonPrimitive(it.lon))
                    },
                )
            }
            el.tags?.let { tags ->
                put(
                    "tags",
                    JsonObject(tags.mapValues { (_, v) -> JsonPrimitive(v) }),
                )
            }
        }

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

    private fun parseFetchedAt(envelope: Envelope): Instant =
        try {
            Instant.parse(envelope.fetchedAt)
        } catch (e: Exception) {
            Instant.now()
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}

// DTO mirroring Overpass's response shape. `_fetchedAt` is set by the ETL
// after deserialization (it isn't on the wire — comes from the envelope).
@Serializable
data class PlanetFitnessRawDto(
    val elements: List<OverpassElement> = emptyList(),
    @kotlinx.serialization.Transient val _fetchedAt: Instant = Instant.EPOCH,
)

@Serializable
data class OverpassElement(
    val type: String, // "node" | "way" | "relation"
    val id: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val center: OverpassCenter? = null,
    val tags: Map<String, String>? = null,
)

@Serializable
data class OverpassCenter(
    val lat: Double,
    val lon: Double,
)
