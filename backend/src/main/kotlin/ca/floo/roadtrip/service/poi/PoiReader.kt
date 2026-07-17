package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.model.api.poi.PoiDetailFeatureSchema
import ca.floo.roadtrip.model.api.poi.PoiFeatureCollectionSchema
import ca.floo.roadtrip.model.api.poi.PoiSearchResponseSchema
import ca.floo.roadtrip.model.domain.poi.Bbox
import ca.floo.roadtrip.model.domain.poi.PoiRow

internal interface PoiReader {
    fun pois(
        bbox: Bbox,
        zoom: Int?,
        categories: List<String>?,
    ): PoiFeatureCollectionSchema

    fun poisWithinPolygon(
        polygonGeoJson: String,
        categories: List<String>?,
    ): List<PoiRow>

    fun poiDetail(id: Long): PoiDetailFeatureSchema?

    fun search(
        query: String,
        categories: List<String>,
        limit: Int,
    ): PoiSearchResponseSchema
}
