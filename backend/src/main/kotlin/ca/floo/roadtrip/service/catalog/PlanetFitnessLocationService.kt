package ca.floo.roadtrip.service.catalog

import ca.floo.roadtrip.models.domain.PoiDetailRow
import ca.floo.roadtrip.models.domain.PoiIndexRow
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo

private const val PLANET_FITNESS_LOCATION_POI_TYPE = "planet_fitness_location"

internal class PlanetFitnessLocationService(
    private val repo: PlanetFitnessLocationRepo,
) {
    fun poiDetail(poi: PoiIndexRow): PoiDetailRow? {
        val detail = repo.findPoiDetailByPoi(poi.id) ?: return null
        val location = detail.location
        return PoiDetailRow(
            id = poi.id,
            source = PLANET_FITNESS_LOCATION_POI_TYPE,
            sourceId = location.locationId,
            category = PLANET_FITNESS_LOCATION_POI_TYPE,
            subcategory = null,
            name = location.name,
            region = location.region,
            country = location.country,
            lng = poi.lng,
            lat = poi.lat,
            unitName = null,
            reserveUrl = null,
            phone = location.phone,
            infoUrl = location.infoUrl,
            addressJson = location.address.toString(),
            geomJson = poi.geomJson,
            propertiesJson = detail.propertiesJson,
        )
    }
}
