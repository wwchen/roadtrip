package ca.floo.roadtrip.service.catalog

import ca.floo.roadtrip.models.api.PoiCategoryDetailSchema
import ca.floo.roadtrip.models.api.PoiDetailFeatureSchema
import ca.floo.roadtrip.models.api.PoiDetailPropertiesSchema
import ca.floo.roadtrip.models.domain.PoiIndexRow
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.service.api.PLANET_FITNESS_LOCATION_POI_TYPE
import kotlinx.serialization.json.Json

internal class PlanetFitnessLocationService(
    private val repo: PlanetFitnessLocationRepo,
) {
    fun poiDetailFeature(poi: PoiIndexRow): PoiDetailFeatureSchema? {
        val detail = repo.findPoiDetailByPoi(poi.id) ?: return null
        val location = detail.location
        val raw = Json.parseToJsonElement(detail.propertiesJson)
        return PoiDetailFeatureSchema(
            id = poi.id,
            geometry = Json.parseToJsonElement(poi.geomJson),
            properties =
                PoiDetailPropertiesSchema(
                    source = PLANET_FITNESS_LOCATION_POI_TYPE,
                    sourceId = location.locationId,
                    category = PLANET_FITNESS_LOCATION_POI_TYPE,
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
                ),
        )
    }
}
