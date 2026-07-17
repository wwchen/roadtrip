package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.model.api.poi.PoiDetailFeatureSchema
import ca.floo.roadtrip.model.api.poi.PoiFeatureCollectionSchema
import ca.floo.roadtrip.model.api.poi.PoiSearchHitSchema
import ca.floo.roadtrip.model.api.poi.PoiSearchResponseSchema
import ca.floo.roadtrip.model.api.poi.PointGeometrySchema
import ca.floo.roadtrip.model.api.poi.SlimPoiFeatureSchema
import ca.floo.roadtrip.model.api.poi.SlimPoiPropertiesSchema
import ca.floo.roadtrip.model.domain.poi.Bbox
import ca.floo.roadtrip.model.domain.poi.PoiRow
import ca.floo.roadtrip.repo.PoiServingRepo
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Hard cap. The Mapbox/MapLibre frontend chokes on >5k features per source,
// and our POIs cover all of US/CA — the user is expected to zoom in. Returning
// truncated=true tells the client to ask the user to zoom further.
internal const val POI_LIMIT: Int = 2000

private const val MIN_SEARCH_QUERY_LENGTH = 2

@OptIn(ExperimentalSerializationApi::class)
private val poiFeatureJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

private val POI_CATEGORY_ALIASES =
    mapOf(
        "planet-fitness" to PlanetFitnessLocationService.POI_TYPE,
        "supercharger" to TeslaSuperchargerService.POI_TYPE,
    )

internal fun canonicalPoiCategory(category: String): String = POI_CATEGORY_ALIASES[category] ?: category

internal fun canonicalPoiCategories(categories: List<String>): List<String> =
    categories
        .map(::canonicalPoiCategory)
        .distinct()

internal class PoiService(
    private val poiRepo: PoiServingRepo,
    detailServices: List<PoiDetailService>,
    private val categoryPolicies: List<PoiCategoryPolicy> = DEFAULT_POI_CATEGORY_POLICIES,
    private val limit: Int = POI_LIMIT,
) : PoiReader {
    private val detailServicesByType = detailServices.associateBy { it.poiType }
    private val categoryPoliciesByType = categoryPolicies.associateBy { it.poiType }
    private val defaultCategories = categoryPolicies.map { it.poiType }

    override fun pois(
        bbox: Bbox,
        zoom: Int?,
        categories: List<String>?,
    ): PoiFeatureCollectionSchema {
        val requestedCategories = visibleCategories(categories, zoom)
        if (requestedCategories.isEmpty()) return poiFeatureCollection(emptyList(), truncated = false)
        val result =
            poiRepo.fetchPois(
                bbox = bbox,
                categories = requestedCategories,
                defaultCategories = defaultCategories,
                limit = limit,
            )
        return poiFeatureCollection(result.rows, result.truncated)
    }

    override fun poisWithinPolygon(
        polygonGeoJson: String,
        categories: List<String>?,
    ): List<PoiRow> {
        val requestedCategories = categories?.let(::canonicalPoiCategories) ?: defaultCategories
        if (requestedCategories.isEmpty()) return emptyList()
        return poiRepo.fetchPoisWithinPolygon(
            polygonGeoJson = polygonGeoJson,
            categories = requestedCategories,
        )
    }

    override fun poiDetail(id: Long): PoiDetailFeatureSchema? {
        val poi = poiRepo.findById(id) ?: return null
        val properties =
            detailServicesByType[poi.category]
                ?.poiDetailProperties(poi)
                ?: return null
        return PoiDetailFeatureSchema(
            id = poi.id,
            geometry = Json.parseToJsonElement(poi.geomJson),
            properties = properties,
        )
    }

    override fun search(
        query: String,
        categories: List<String>,
        limit: Int,
    ): PoiSearchResponseSchema {
        if (query.length < MIN_SEARCH_QUERY_LENGTH) return PoiSearchResponseSchema(results = emptyList())
        val canonicalCategories = canonicalPoiCategories(categories)
        val hits =
            poiRepo
                .search(
                    query = query,
                    categories = canonicalCategories,
                    limit = limit,
                ).map {
                    PoiSearchHitSchema(
                        id = it.id,
                        name = it.name,
                        category = it.category,
                        region = it.region,
                        lng = it.lng,
                        lat = it.lat,
                    )
                }
        return PoiSearchResponseSchema(results = hits)
    }

    private fun visibleCategories(
        categories: List<String>?,
        zoom: Int?,
    ): List<String> =
        (categories?.let(::canonicalPoiCategories) ?: defaultCategories)
            .filter { category -> categoryPoliciesByType[category]?.isVisibleAtZoom(zoom) ?: true }
}

internal fun poiFeatureCollection(
    rows: List<PoiRow>,
    truncated: Boolean,
): PoiFeatureCollectionSchema =
    PoiFeatureCollectionSchema(
        truncated = truncated,
        features =
            rows.map { row ->
                SlimPoiFeatureSchema(
                    id = row.id,
                    geometry = PointGeometrySchema(coordinates = listOf(row.lng, row.lat)),
                    properties =
                        SlimPoiPropertiesSchema(
                            category = row.category,
                            subcategory = row.subcategory,
                            agency = row.agency,
                        ),
                )
            },
    )

internal fun encodePoiFeatureJson(value: PoiFeatureCollectionSchema): String = poiFeatureJson.encodeToString(value)

internal fun encodePoiFeatureJson(value: PoiDetailFeatureSchema): String = poiFeatureJson.encodeToString(value)
