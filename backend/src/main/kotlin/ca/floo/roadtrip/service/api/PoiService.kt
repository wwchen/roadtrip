package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.api.PoiDetailFeatureSchema
import ca.floo.roadtrip.models.api.PoiDetailPropertiesSchema
import ca.floo.roadtrip.models.api.PoiFeatureCollectionSchema
import ca.floo.roadtrip.models.api.PoiSearchHitSchema
import ca.floo.roadtrip.models.api.PoiSearchResponseSchema
import ca.floo.roadtrip.models.api.PointGeometrySchema
import ca.floo.roadtrip.models.api.SlimPoiFeatureSchema
import ca.floo.roadtrip.models.api.SlimPoiPropertiesSchema
import ca.floo.roadtrip.models.domain.Bbox
import ca.floo.roadtrip.models.domain.PoiDetailRow
import ca.floo.roadtrip.models.domain.PoiRow
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.provider.ProviderRefParser
import ca.floo.roadtrip.service.catalog.CampgroundService
import ca.floo.roadtrip.service.catalog.PlanetFitnessLocationService
import ca.floo.roadtrip.service.catalog.TeslaSuperchargerService
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Hard cap. The Mapbox/MapLibre frontend chokes on >5k features per source,
// and our POIs cover all of US/CA — the user is expected to zoom in. Returning
// truncated=true tells the client to ask the user to zoom further.
internal const val POI_LIMIT: Int = 2000

// Below this zoom, the campground category is suppressed regardless of what's
// in the categories list. ~12k rows nationwide — not useful at continental
// zoom and they crowd out the per-category limit budget.
internal const val POI_CAMPGROUND_MIN_ZOOM: Int = 6

private const val MIN_SEARCH_QUERY_LENGTH = 2
private const val CAMPGROUND_POI_TYPE = "campground"
private const val TESLA_SUPERCHARGER_POI_TYPE = "tesla_supercharger"
private const val PLANET_FITNESS_LOCATION_POI_TYPE = "planet_fitness_location"

private val DEFAULT_POI_TYPES =
    listOf(
        CAMPGROUND_POI_TYPE,
        TESLA_SUPERCHARGER_POI_TYPE,
        PLANET_FITNESS_LOCATION_POI_TYPE,
    )

@OptIn(ExperimentalSerializationApi::class)
private val poiFeatureJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

private val POI_CATEGORY_ALIASES =
    mapOf(
        "planet-fitness" to PLANET_FITNESS_LOCATION_POI_TYPE,
        "supercharger" to TESLA_SUPERCHARGER_POI_TYPE,
    )

internal fun canonicalPoiCategory(category: String): String = POI_CATEGORY_ALIASES[category] ?: category

internal fun canonicalPoiCategories(categories: List<String>): List<String> =
    categories
        .map(::canonicalPoiCategory)
        .distinct()

internal class PoiService(
    private val poiRepo: PoiServingRepo,
    private val campgroundService: CampgroundService,
    private val teslaSuperchargerService: TeslaSuperchargerService,
    private val planetFitnessLocationService: PlanetFitnessLocationService,
    private val dateResolver: AvailabilityDateResolver = AvailabilityDateResolver(),
    private val availabilitySupport: (PoiDetailRow) -> Boolean = ::providerRefShapeSupportsAvailability,
    private val availabilityProvider: (PoiDetailRow) -> String? = { null },
    private val limit: Int = POI_LIMIT,
    private val defaultCategories: List<String> = DEFAULT_POI_TYPES,
) {
    fun pois(
        bbox: Bbox,
        zoom: Int?,
        categories: List<String>?,
    ): PoiFeatureCollectionSchema {
        val shouldSuppressCampgrounds = zoom != null && zoom < POI_CAMPGROUND_MIN_ZOOM
        val effectiveDefaultCategories =
            if (shouldSuppressCampgrounds) {
                defaultCategories.filter { it != CAMPGROUND_POI_TYPE }
            } else {
                defaultCategories
            }
        val requestedCategories =
            categories
                ?.let(::canonicalPoiCategories)
                ?.let { canonical ->
                    if (shouldSuppressCampgrounds) {
                        canonical.filter { it != CAMPGROUND_POI_TYPE }
                    } else {
                        canonical
                    }
                }
        val result =
            poiRepo.fetchPois(
                bbox = bbox,
                categories = requestedCategories,
                defaultCategories = effectiveDefaultCategories,
                limit = limit,
            )
        return poiFeatureCollection(result.rows, result.truncated)
    }

    fun poiDetail(id: Long): PoiDetailFeatureSchema? {
        val poi = poiRepo.findById(id) ?: return null
        val row =
            when (poi.category) {
                CAMPGROUND_POI_TYPE -> campgroundService.poiDetail(poi)
                TESLA_SUPERCHARGER_POI_TYPE -> teslaSuperchargerService.poiDetail(poi)
                PLANET_FITNESS_LOCATION_POI_TYPE -> planetFitnessLocationService.poiDetail(poi)
                else -> null
            } ?: return null
        return poiDetailFeature(
            r = row,
            dateResolver = dateResolver,
            availabilitySupported = availabilitySupport(row),
            availabilityProvider = availabilityProvider(row),
        )
    }

    fun search(
        query: String,
        categories: List<String>,
        limit: Int,
    ): PoiSearchResponseSchema {
        if (query.length < MIN_SEARCH_QUERY_LENGTH) return PoiSearchResponseSchema(results = emptyList())
        val canonicalCategories = canonicalPoiCategories(categories)
        val hits =
            poiRepo
                .search(query = query, categories = canonicalCategories, limit = limit)
                .map {
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

internal fun poiDetailFeature(
    r: PoiDetailRow,
    dateResolver: AvailabilityDateResolver = AvailabilityDateResolver(),
    availabilitySupported: Boolean = providerRefShapeSupportsAvailability(r),
    availabilityProvider: String? = null,
): PoiDetailFeatureSchema {
    val raw = Json.parseToJsonElement(r.propertiesJson)
    val rawObject = raw as? JsonObject ?: JsonObject(emptyMap())
    val dateContext =
        if (r.category == CAMPGROUND_POI_TYPE) {
            dateResolver.context(lat = r.lat, lng = r.lng)
        } else {
            null
        }
    return PoiDetailFeatureSchema(
        id = r.id,
        geometry = Json.parseToJsonElement(r.geomJson),
        properties =
            PoiDetailPropertiesSchema(
                source = r.source,
                sourceId = r.sourceId,
                sources = r.memberSources,
                availabilityProvider = availabilityProvider,
                category = r.category,
                subcategory = r.subcategory,
                agency = r.agency,
                name = r.name,
                region = r.region,
                country = r.country,
                timeZone = dateContext?.timeZone?.id,
                earliestDate = dateContext?.earliestDate?.toString(),
                unitName = r.unitName,
                reserveUrl = r.reserveUrl,
                bookingSite = r.reserveUrl?.let(UrlHosts::extract),
                phone = r.phone,
                infoUrl = r.infoUrl,
                address = r.addressJson?.let { Json.parseToJsonElement(it) },
                description = rawObject.stringProperty("description"),
                photoUrl = rawObject.stringProperty("photo_url"),
                providerRef = r.providerRefJson?.let { Json.parseToJsonElement(it) },
                availabilitySupported = availabilitySupported.takeIf { it },
                cta = PoiCta.Default.computeCta(r),
                bookingSystem = PoiCta.Default.bookingSystem(r),
                raw = raw,
            ),
    )
}

private fun JsonObject.stringProperty(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun providerRefShapeSupportsAvailability(r: PoiDetailRow): Boolean =
    r.category == CAMPGROUND_POI_TYPE &&
        r.providerRefJson?.let { ProviderRefParser.parse(it) } != null

internal fun encodePoiFeatureJson(value: PoiFeatureCollectionSchema): String = poiFeatureJson.encodeToString(value)

internal fun encodePoiFeatureJson(value: PoiDetailFeatureSchema): String = poiFeatureJson.encodeToString(value)
