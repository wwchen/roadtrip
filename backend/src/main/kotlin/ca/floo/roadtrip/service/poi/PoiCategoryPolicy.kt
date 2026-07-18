package ca.floo.roadtrip.service.poi

internal class PoiCategoryPolicy(
    val poiType: String,
    val minZoom: Int? = null,
) {
    fun isVisibleAtZoom(zoom: Int?): Boolean = minZoom == null || zoom == null || zoom >= minZoom
}

internal val defaultPoiCategoryPolicies =
    listOf(
        PoiCategoryPolicy(CampgroundService.POI_TYPE, minZoom = CampgroundService.MIN_POI_ZOOM),
        PoiCategoryPolicy(TeslaSuperchargerService.POI_TYPE),
        PoiCategoryPolicy(PlanetFitnessLocationService.POI_TYPE),
    )

internal val defaultPoiTypes: List<String> = defaultPoiCategoryPolicies.map { it.poiType }
