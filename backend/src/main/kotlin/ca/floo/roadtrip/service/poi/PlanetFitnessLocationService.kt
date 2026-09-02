package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.model.api.poi.PoiCategoryDetailSchema
import ca.floo.roadtrip.model.api.poi.PoiDetailPropertiesSchema
import ca.floo.roadtrip.model.domain.poi.PoiIndexRow
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal class PlanetFitnessLocationService(
    private val planetFitnessLocationRepo: PlanetFitnessLocationRepo,
) : PoiDetailService {
    override val poiType: String = POI_TYPE

    override fun poiDetailProperties(poi: PoiIndexRow): PoiDetailPropertiesSchema? {
        val detail = planetFitnessLocationRepo.findPoiDetailByPoi(poi.id) ?: return null
        val location = detail.location
        val raw = Json.parseToJsonElement(detail.propertiesJson)
        return PoiDetailPropertiesSchema(
            source = POI_TYPE,
            sourceId = location.locationId,
            category = POI_TYPE,
            name = location.name,
            region = location.region,
            country = location.country,
            detail =
                PoiCategoryDetailSchema(
                    phone = location.phone,
                    infoUrl = location.infoUrl,
                    openingHours = location.openingHours,
                    brand = location.brand,
                    address = location.address,
                    upstream = upstreamTags(location.payload),
                    raw = raw,
                ),
        )
    }

    /**
     * The OSM tag map, lifted out of the captured Overpass element.
     *
     * This is the only place outside `PlanetFitnessEtl` that knows the vendor
     * keeps its facts as tags, and the knowledge stops here: callers get a plain
     * key/value object for the drawer's "Upstream data" table. Null for an
     * element that carried no tags, so the table drops out instead of rendering
     * empty.
     */
    private fun upstreamTags(payload: JsonElement): JsonObject? =
        ((payload as? JsonObject)?.get(PAYLOAD_TAGS_KEY) as? JsonObject)?.takeIf { it.isNotEmpty() }

    companion object {
        const val POI_TYPE = "planet_fitness_location"
        private const val PAYLOAD_TAGS_KEY = "tags"
    }
}
