package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// /api/pois request body — bbox is required (4 numbers, [w,s,e,n]),
// zoom + categories optional. Corridor filtering lives behind
// POST /api/pois/on-route.
@Serializable
data class PoisRequestSchema(
    val bbox: List<Double>,
    val zoom: Int? = null,
    val categories: List<String>? = null,
)

// /api/pois response. Slim GeoJSON used for viewport map rendering.
@Serializable
data class PoiFeatureCollectionSchema(
    val type: String = "FeatureCollection",
    val truncated: Boolean,
    val features: List<SlimPoiFeatureSchema>,
)

@Serializable
data class SlimPoiFeatureSchema(
    val type: String = "Feature",
    val id: Long,
    val geometry: PointGeometrySchema,
    val properties: SlimPoiPropertiesSchema,
)

@Serializable
data class SlimPoiPropertiesSchema(
    val category: String,
    val subcategory: String? = null,
)

// /api/pois/{id} response. Wide GeoJSON used for pin popups/drawers.
@Serializable
data class PoiDetailFeatureSchema(
    val type: String = "Feature",
    val id: Long,
    val geometry: JsonElement,
    val properties: PoiDetailPropertiesSchema,
)

@Serializable
data class PoiDetailPropertiesSchema(
    val source: String,
    @SerialName("source_id") val sourceId: String,
    val category: String,
    val subcategory: String? = null,
    val name: String,
    val region: String? = null,
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("reserve_url") val reserveUrl: String? = null,
    val phone: String? = null,
    @SerialName("info_url") val infoUrl: String? = null,
    val address: JsonElement? = null,
    @SerialName("provider_ref") val providerRef: JsonElement? = null,
    val raw: JsonElement,
)

// /api/pois/on-route request body. Same {waypoints, radius_miles}
// shape the trip planner already uses; categories optional and
// defaults to all enabled poi_data categories on the server.
@Serializable
data class PoisOnRouteRequestSchema(
    val waypoints: List<WaypointSchema>,
    val radius_miles: Double,
    val categories: List<String>? = null,
)

// /api/pois/on-route response. A slim GeoJSON FeatureCollection, ordered by
// route_km and intentionally missing bbox-only metadata such as truncated.
@Serializable
data class PoisOnRouteResponseSchema(
    val type: String = "FeatureCollection",
    val features: List<PoisOnRouteFeatureSchema>,
)

@Serializable
data class PoisOnRouteFeatureSchema(
    val type: String = "Feature",
    val id: Long,
    val geometry: PointGeometrySchema,
    val properties: PoisOnRouteFeaturePropertiesSchema,
)

@Serializable
data class PointGeometrySchema(
    val type: String = "Point",
    val coordinates: List<Double>,
)

@Serializable
data class PoisOnRouteFeaturePropertiesSchema(
    val category: String,
    val subcategory: String? = null,
    @SerialName("route_km") val routeKm: Double,
)

@Serializable
data class WaypointSchema(
    val lat: Double,
    val lng: Double,
)

// /api/pois/search response. One row per match; consumer (the topbar) needs
// just enough to render the dropdown row + drive a flyTo + synthesized
// click. Anything richer can be fetched on click via /api/pois/{id}.
@Serializable
data class PoiSearchHitSchema(
    val id: Long,
    val name: String,
    val category: String,
    val region: String? = null,
    val lng: Double,
    val lat: Double,
)

@Serializable
data class PoiSearchResponseSchema(
    val results: List<PoiSearchHitSchema>,
)
