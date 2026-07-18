package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.model.api.poi.PoiCategoryDetailSchema
import ca.floo.roadtrip.model.api.poi.PoiDetailPropertiesSchema
import ca.floo.roadtrip.model.domain.poi.PoiIndexRow
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import kotlinx.serialization.json.Json

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
                    address = location.address,
                    raw = raw,
                ),
        )
    }

    companion object {
        const val POI_TYPE = "planet_fitness_location"
    }
}
