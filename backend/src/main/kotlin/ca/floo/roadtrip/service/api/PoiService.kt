package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.api.PoiCategoryDetailSchema
import ca.floo.roadtrip.models.api.PoiDetailFeatureSchema
import ca.floo.roadtrip.models.api.PoiDetailPropertiesSchema
import ca.floo.roadtrip.models.api.PoiFeatureCollectionSchema
import ca.floo.roadtrip.models.api.PoiSearchHitSchema
import ca.floo.roadtrip.models.api.PoiSearchResponseSchema
import ca.floo.roadtrip.models.api.PointGeometrySchema
import ca.floo.roadtrip.models.api.SlimPoiFeatureSchema
import ca.floo.roadtrip.models.api.SlimPoiPropertiesSchema
import ca.floo.roadtrip.models.domain.Bbox
import ca.floo.roadtrip.models.domain.CampgroundPoiDetail
import ca.floo.roadtrip.models.domain.PlanetFitnessLocationPoiDetail
import ca.floo.roadtrip.models.domain.PoiIndexRow
import ca.floo.roadtrip.models.domain.PoiRow
import ca.floo.roadtrip.models.domain.TeslaSuperchargerPoiDetail
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
private const val REGION_KEY = "region"
private const val COUNTRY_KEY = "country"
private const val AGENCY_KEY = "agency"
private const val PHONE_KEY = "phone"
private const val URL_KEY = "url"

internal val DEFAULT_POI_TYPES =
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
    private val campgroundRepo: CampgroundRepo,
    private val teslaSuperchargerRepo: TeslaSuperchargerRepo,
    private val planetFitnessLocationRepo: PlanetFitnessLocationRepo,
    private val dateResolver: AvailabilityDateResolver = AvailabilityDateResolver(),
    private val availabilitySupport: (Long) -> Boolean = { false },
    private val availabilityProvider: (Long) -> String? = { null },
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

    fun poisWithinPolygon(
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

    fun poiDetail(id: Long): PoiDetailFeatureSchema? {
        val poi = poiRepo.findById(id) ?: return null
        return when (poi.category) {
            CAMPGROUND_POI_TYPE ->
                campgroundRepo.findPoiDetailByPoi(poi.id)?.let { detail ->
                    campgroundPoiDetailFeature(
                        poi = poi,
                        detail = detail,
                        dateResolver = dateResolver,
                        availabilitySupported = availabilitySupport(poi.id),
                        availabilityProvider = availabilityProvider(poi.id),
                    )
                }
            TESLA_SUPERCHARGER_POI_TYPE ->
                teslaSuperchargerRepo.findPoiDetailByPoi(poi.id)?.let { detail ->
                    teslaSuperchargerPoiDetailFeature(poi, detail)
                }
            PLANET_FITNESS_LOCATION_POI_TYPE ->
                planetFitnessLocationRepo.findPoiDetailByPoi(poi.id)?.let { detail ->
                    planetFitnessLocationPoiDetailFeature(poi, detail)
                }
            else -> null
        }
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

internal fun campgroundPoiDetailFeature(
    poi: PoiIndexRow,
    detail: CampgroundPoiDetail,
    dateResolver: AvailabilityDateResolver = AvailabilityDateResolver(),
    availabilitySupported: Boolean = false,
    availabilityProvider: String? = null,
): PoiDetailFeatureSchema {
    val campground = detail.campground
    val raw = Json.parseToJsonElement(detail.propertiesJson)
    val rawObject = raw as? JsonObject ?: JsonObject(emptyMap())
    val dateContext = dateResolver.context(lat = poi.lat, lng = poi.lng)
    val infoUrl = campground.links.firstObjectStringProperty(URL_KEY)
    val description = rawObject.stringProperty("description")
    val photoUrl = rawObject.stringProperty("photo_url")
    return PoiDetailFeatureSchema(
        id = poi.id,
        geometry = Json.parseToJsonElement(poi.geomJson),
        properties =
            PoiDetailPropertiesSchema(
                source = detail.source,
                sourceId = detail.sourceId,
                category = CAMPGROUND_POI_TYPE,
                subcategory = campground.kind,
                agency = campground.management.stringProperty(AGENCY_KEY),
                name = campground.name,
                region = campground.location.stringProperty(REGION_KEY),
                country = campground.location.stringProperty(COUNTRY_KEY),
                detail =
                    PoiCategoryDetailSchema(
                        sources = detail.memberSources,
                        availabilityProvider = availabilityProvider,
                        timeZone = dateContext.timeZone.id,
                        earliestDate = dateContext.earliestDate.toString(),
                        unitName = null,
                        reserveUrl = campground.reservationUrl,
                        bookingSite = campground.reservationUrl?.let(UrlHosts::extract),
                        phone = campground.contact.stringProperty(PHONE_KEY),
                        infoUrl = infoUrl,
                        address = campground.location,
                        description = description,
                        photoUrl = photoUrl,
                        providerRef = detail.providerRefJson?.let { Json.parseToJsonElement(it) },
                        availabilitySupported = availabilitySupported.takeIf { it },
                        cta =
                            PoiCta.Default.computeCta(
                                providerRefJson = detail.providerRefJson,
                                ctaProviderRefJson = detail.ctaProviderRefJson,
                                reserveUrl = campground.reservationUrl,
                                infoUrl = infoUrl,
                            ),
                        bookingSystem =
                            PoiCta.Default.bookingSystem(
                                providerRefJson = detail.providerRefJson,
                                reserveUrl = campground.reservationUrl,
                                infoUrl = infoUrl,
                            ),
                        raw = raw,
                    ),
            ),
    )
}

internal fun teslaSuperchargerPoiDetailFeature(
    poi: PoiIndexRow,
    detail: TeslaSuperchargerPoiDetail,
): PoiDetailFeatureSchema {
    val supercharger = detail.supercharger
    val raw = Json.parseToJsonElement(detail.propertiesJson)
    return PoiDetailFeatureSchema(
        id = poi.id,
        geometry = Json.parseToJsonElement(poi.geomJson),
        properties =
            PoiDetailPropertiesSchema(
                source = TESLA_SUPERCHARGER_POI_TYPE,
                sourceId = supercharger.locationSlug,
                category = TESLA_SUPERCHARGER_POI_TYPE,
                name = supercharger.commonSiteName,
                region = supercharger.region,
                country = supercharger.country,
                detail =
                    PoiCategoryDetailSchema(
                        infoUrl = supercharger.infoUrl,
                        address = supercharger.address,
                        raw = raw,
                    ),
            ),
    )
}

internal fun planetFitnessLocationPoiDetailFeature(
    poi: PoiIndexRow,
    detail: PlanetFitnessLocationPoiDetail,
): PoiDetailFeatureSchema {
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

private fun JsonObject.stringProperty(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun JsonElement.stringProperty(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun JsonElement.firstObjectStringProperty(key: String): String? =
    (this as? JsonArray)
        ?.firstNotNullOfOrNull { element ->
            (element as? JsonObject)
                ?.let { (it[key] as? JsonPrimitive)?.contentOrNull }
                ?.trim()
                ?.takeIf { value -> value.isNotEmpty() }
        }

internal fun encodePoiFeatureJson(value: PoiFeatureCollectionSchema): String = poiFeatureJson.encodeToString(value)

internal fun encodePoiFeatureJson(value: PoiDetailFeatureSchema): String = poiFeatureJson.encodeToString(value)
