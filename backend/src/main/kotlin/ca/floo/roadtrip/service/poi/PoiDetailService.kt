package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.model.api.poi.PoiDetailPropertiesSchema
import ca.floo.roadtrip.model.domain.poi.PoiIndexRow

internal interface PoiDetailService {
    val poiType: String

    fun poiDetailProperties(poi: PoiIndexRow): PoiDetailPropertiesSchema?
}
