package ca.floo.roadtrip.service.readpath

import ca.floo.roadtrip.config.ReadPathProviderConfig
import ca.floo.roadtrip.model.api.poi.PoiDetailFeatureSchema
import ca.floo.roadtrip.model.api.poi.PoiFeatureCollectionSchema
import ca.floo.roadtrip.model.api.poi.PoiSearchHitSchema
import ca.floo.roadtrip.model.api.poi.PoiSearchResponseSchema
import ca.floo.roadtrip.model.api.poi.SlimPoiFeatureSchema
import ca.floo.roadtrip.model.domain.poi.Bbox
import ca.floo.roadtrip.model.domain.poi.PoiIndexRow
import ca.floo.roadtrip.model.domain.poi.PoiRow
import ca.floo.roadtrip.service.poi.PoiDetailService
import ca.floo.roadtrip.service.poi.PoiReader

internal class ReadPathProviderPoiReader(
    private val delegate: PoiReader,
    detailServices: List<PoiDetailService>,
    private val providers: ReadPathProviderConfig,
) : PoiReader {
    private val detailServicesByType = detailServices.associateBy { it.poiType }

    override fun pois(
        bbox: Bbox,
        zoom: Int?,
        categories: List<String>?,
    ): PoiFeatureCollectionSchema {
        val collection = delegate.pois(bbox = bbox, zoom = zoom, categories = categories)
        return collection.copy(features = collection.features.filter(::isEnabledDataSource))
    }

    override fun poisWithinPolygon(
        polygonGeoJson: String,
        categories: List<String>?,
    ): List<PoiRow> {
        val rows = delegate.poisWithinPolygon(polygonGeoJson = polygonGeoJson, categories = categories)
        return rows.filter(::isEnabledDataSource)
    }

    override fun poiDetail(id: Long): PoiDetailFeatureSchema? {
        val feature = delegate.poiDetail(id) ?: return null
        return feature.takeIf { providers.isDataSourceEnabled(it.properties.source) }
    }

    override fun search(
        query: String,
        categories: List<String>,
        limit: Int,
    ): PoiSearchResponseSchema {
        val response = delegate.search(query = query, categories = categories, limit = limit)
        return response.copy(results = response.results.filter(::isEnabledDataSource))
    }

    private fun isEnabledDataSource(row: PoiRow): Boolean =
        sourceFor(
            id = row.id,
            category = row.category,
            lng = row.lng,
            lat = row.lat,
        )?.let(providers::isDataSourceEnabled) == true

    private fun isEnabledDataSource(hit: PoiSearchHitSchema): Boolean =
        sourceFor(
            id = hit.id,
            category = hit.category,
            lng = hit.lng,
            lat = hit.lat,
        )?.let(providers::isDataSourceEnabled) == true

    private fun isEnabledDataSource(feature: SlimPoiFeatureSchema): Boolean =
        sourceFor(
            id = feature.id,
            category = feature.properties.category,
            lng = feature.geometry.coordinates.getOrNull(LNG_INDEX) ?: return false,
            lat = feature.geometry.coordinates.getOrNull(LAT_INDEX) ?: return false,
        )?.let(providers::isDataSourceEnabled) == true

    private fun sourceFor(
        id: Long,
        category: String,
        lng: Double,
        lat: Double,
    ): String? =
        detailServicesByType[category]
            ?.poiDetailProperties(
                PoiIndexRow(
                    id = id,
                    category = category,
                    lng = lng,
                    lat = lat,
                    geomJson = EMPTY_GEOMETRY_JSON,
                ),
            )?.source

    private companion object {
        private const val LNG_INDEX = 0
        private const val LAT_INDEX = 1
        private const val EMPTY_GEOMETRY_JSON = "{}"
    }
}
