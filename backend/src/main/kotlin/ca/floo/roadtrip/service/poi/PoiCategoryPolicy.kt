package ca.floo.roadtrip.service.poi

internal data class PoiCategoryPolicy(
    val poiType: String,
    val minZoom: Int? = null,
) {
    fun isVisibleAtZoom(zoom: Int?): Boolean = minZoom == null || zoom == null || zoom >= minZoom
}

internal val DEFAULT_POI_CATEGORY_POLICIES =
    listOf(
        PoiCategoryPolicy(CampgroundService.POI_TYPE, minZoom = CampgroundService.MIN_POI_ZOOM),
        PoiCategoryPolicy(TeslaSuperchargerService.POI_TYPE),
        PoiCategoryPolicy(PlanetFitnessLocationService.POI_TYPE),
    )

internal val DEFAULT_POI_TYPES: List<String> = DEFAULT_POI_CATEGORY_POLICIES.map { it.poiType }
