package ca.floo.roadtrip.api

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext

// Hard cap. The Mapbox/MapLibre frontend chokes on >5k features per source,
// and our POIs cover all of US/CA — the user is expected to zoom in. Returning
// truncated=true tells the client to ask the user to zoom further.
const val POI_LIMIT: Int = 2000

// Below this zoom, the campground category is suppressed regardless of what's
// in the categories list. ~12k rows nationwide — not useful at continental
// zoom and they crowd out the per-category limit budget.
private const val CG_MIN_ZOOM: Int = 6

// Polygon body cap. A 4-waypoint cross-country corridor at 30mi after
// turf.simplify is ~500 vertices. 2000 is a generous ceiling that still
// keeps the request body well under common reverse-proxy limits.
private const val MAX_POLYGON_VERTICES: Int = 2000

// POST /api/pois
//
// Request body (JSON):
//   {
//     "bbox": [west, south, east, north],            // required
//     "zoom": 8,                                     // optional; gates CG below CG_MIN_ZOOM
//     "categories": ["campground", ...],             // optional; defaults to all
//     "polygon": {                                   // optional
//       "type": "Polygon",
//       "coordinates": [[[lng,lat], [lng,lat], ...]]
//     }
//   }
//
// Returns a GeoJSON FeatureCollection with truncated:true when the bbox spans
// more rows than POI_LIMIT.  When polygon is present, results are filtered to
// pois inside it (ST_Within) — used by the trip-planner corridor view.
//
// Why POST: the polygon for a long routed corridor is too big for a query
// string (encoded ~tens of kb after turf.buffer). POST removes URL length as
// a constraint and we trade HTTP cacheability for it; the FE bbox-on-pan is
// already debounced 250ms so the cache loss is negligible.
fun Route.poiRoutes(ctx: DSLContext) {
    post("/api/pois", {
        tags = listOf("poi")
        summary = "GeoJSON FeatureCollection within bbox; capped at $POI_LIMIT features (truncated:true on overflow)"
        description = "Body is JSON: { bbox, zoom?, categories?, polygon? }. polygon filters to POIs inside the corridor."
        response {
            code(io.ktor.http.HttpStatusCode.OK) {
                description = "GeoJSON FeatureCollection."
                body<String> { mediaTypes(io.ktor.http.ContentType.Application.Json) }
            }
            code(io.ktor.http.HttpStatusCode.BadRequest) {
                description = "Malformed body, missing bbox, or polygon too large."
                body<String> { mediaTypes(io.ktor.http.ContentType.Application.Json) }
            }
        }
    }) {
        val bodyText = call.receiveText()
        val req =
            try {
                parseRequest(bodyText)
            } catch (e: Exception) {
                call.respondText(
                    """{"error":"bad_request","detail":"${escapeJsonInline(e.message ?: "parse failed")}"}""",
                    io.ktor.http.ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

        val rawCategories = req.categories
        val categories =
            if (rawCategories != null && req.zoom != null && req.zoom < CG_MIN_ZOOM) {
                rawCategories.filter { it != "campground" }.takeIf { it.isNotEmpty() }
            } else {
                rawCategories
            }

        val rows =
            if (categories?.isEmpty() == true) {
                emptyList()
            } else {
                fetchPois(
                    ctx = ctx,
                    bbox = req.bbox,
                    categories = categories,
                    polygonGeoJson = req.polygonGeoJson,
                    limit = POI_LIMIT + 1,
                )
            }
        val truncated = rows.size > POI_LIMIT
        val effective = if (truncated) rows.take(POI_LIMIT) else rows

        call.respondText(
            buildFeatureCollection(effective, truncated, req.polygonGeoJson != null),
            io.ktor.http.ContentType.Application.Json,
        )
    }

    get("/api/pois/health") {
        call.respondText("""{"status":"ok"}""", io.ktor.http.ContentType.Application.Json)
    }
}

private data class PoiRequest(
    val bbox: Bbox,
    val zoom: Int?,
    val categories: List<String>?,
    val polygonGeoJson: String?, // raw GeoJSON Polygon serialized to text
)

private fun parseRequest(bodyText: String): PoiRequest {
    val root = Json.parseToJsonElement(bodyText).jsonObject

    val bboxArr = root["bbox"]?.jsonArray ?: error("missing bbox")
    require(bboxArr.size == 4) { "bbox must be [west,south,east,north]" }
    val nums = bboxArr.map { it.jsonPrimitive.doubleOrNull ?: error("bbox values must be numbers") }
    val (w, s, e, n) = nums
    require(w in -180.0..180.0 && e in -180.0..180.0) { "bbox lng out of range" }
    require(s in -90.0..90.0 && n in -90.0..90.0) { "bbox lat out of range" }
    require(w < e && s < n) { "bbox: west must be < east, south < north" }
    val bbox = Bbox(w, s, e, n)

    val zoom = root["zoom"]?.jsonPrimitive?.intOrNull

    val categories =
        root["categories"]
            ?.jsonArray
            ?.mapNotNull {
                it.jsonPrimitive.contentOrNull
                    ?.trim()
                    ?.takeIf { c -> c.isNotEmpty() }
            }?.takeIf { it.isNotEmpty() }

    val polygonGeoJson =
        root["polygon"]?.jsonObject?.let { obj ->
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            require(type == "Polygon") { "polygon.type must be 'Polygon', got '$type'" }
            val ringsArr = obj["coordinates"]?.jsonArray ?: error("polygon.coordinates required")
            require(ringsArr.isNotEmpty()) { "polygon must have at least one ring" }
            val outerRing = ringsArr[0].jsonArray
            require(outerRing.size >= 4) { "polygon outer ring must have >= 4 points" }
            val totalVerts = ringsArr.sumOf { it.jsonArray.size }
            require(totalVerts <= MAX_POLYGON_VERTICES) {
                "polygon too large: $totalVerts vertices (max $MAX_POLYGON_VERTICES). Simplify on the client."
            }
            // Serialize the polygon back to a stable JSON string for ST_GeomFromGeoJSON.
            obj.toString()
        }

    return PoiRequest(bbox, zoom, categories, polygonGeoJson)
}

data class Bbox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

internal fun parseBbox(raw: String): Bbox? {
    val parts = raw.split(',')
    if (parts.size != 4) return null
    val nums = parts.map { it.trim().toDoubleOrNull() ?: return null }
    val (w, s, e, n) = nums
    if (w !in -180.0..180.0 || e !in -180.0..180.0) return null
    if (s !in -90.0..90.0 || n !in -90.0..90.0) return null
    if (w >= e || s >= n) return null
    return Bbox(w, s, e, n)
}

internal data class PoiRow(
    val id: Long,
    val source: String,
    val sourceId: String,
    val category: String,
    val name: String,
    val region: String?,
    val unitName: String?,
    val reserveUrl: String?,
    val geomJson: String,
    val propertiesJson: String,
)

internal fun fetchPois(
    ctx: DSLContext,
    bbox: Bbox,
    categories: List<String>?,
    polygonGeoJson: String? = null,
    limit: Int,
): List<PoiRow> {
    // Per-category limit: each requested category gets its own slot budget,
    // so a dataset like Planet Fitness (1.5k rows) can't starve Superchargers
    // or Campgrounds when bbox is continental.
    val cats =
        categories ?: listOf(
            "campground",
            "state-park",
            "national-park",
            "planet-fitness",
            "supercharger",
        )
    if (cats.isEmpty()) return emptyList()

    val perCategoryLimit = (limit / cats.size).coerceAtLeast(1)

    // UNION ALL across N category-scoped subqueries, each capped. Same
    // total round-trip cost as a single query, but truncation is per-cat
    // instead of "first to the row planner."
    val sql =
        buildString {
            cats.forEachIndexed { idx, _ ->
                if (idx > 0) append("\nUNION ALL\n")
                append("(")
                append(
                    """
                    SELECT id, source, source_id, category, name, region, unit_name,
                           reserve_url, ST_AsGeoJSON(geom) AS geom_json,
                           properties::text AS properties_text
                    FROM pois
                    WHERE deleted_at IS NULL
                      AND category = ?
                      AND geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                    """.trimIndent(),
                )
                if (polygonGeoJson != null) {
                    // ST_Within(geom, polygon). Polygon is built once per
                    // subquery from the GeoJSON text. PostGIS optimizes
                    // identical sub-expressions across UNION branches.
                    append("\n      AND ST_Within(geom, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326))")
                }
                append("\n    LIMIT ?")
                append(")")
            }
        }

    val args = mutableListOf<Any>()
    for (cat in cats) {
        args.add(cat)
        args.add(bbox.west)
        args.add(bbox.south)
        args.add(bbox.east)
        args.add(bbox.north)
        if (polygonGeoJson != null) {
            args.add(polygonGeoJson)
        }
        args.add(perCategoryLimit)
    }

    return ctx.fetch(sql, *args.toTypedArray()).map { r ->
        PoiRow(
            id = (r.get("id") as Number).toLong(),
            source = r.get("source") as String,
            sourceId = r.get("source_id") as String,
            category = r.get("category") as String,
            name = r.get("name") as String,
            region = r.get("region") as String?,
            unitName = r.get("unit_name") as String?,
            reserveUrl = r.get("reserve_url") as String?,
            geomJson = r.get("geom_json") as String,
            propertiesJson = r.get("properties_text") as String,
        )
    }
}

internal fun buildFeatureCollection(
    rows: List<PoiRow>,
    truncated: Boolean,
    corridorActive: Boolean = false,
): String {
    // Hand-built JSON. Properties come in as a JSONB ::text and are merged
    // into the feature's properties object — building this with kotlinx
    // would require parsing+re-serializing the JSONB on every row, and the
    // bbox endpoint is hot.
    val sb = StringBuilder()
    sb
        .append("""{"type":"FeatureCollection","truncated":""")
        .append(truncated)
    if (corridorActive) sb.append(""","corridor":true""")
    sb.append(""","features":[""")
    for ((i, r) in rows.withIndex()) {
        if (i > 0) sb.append(',')
        sb.append("""{"type":"Feature","id":""").append(r.id)
        sb.append(""","geometry":""").append(r.geomJson)
        sb.append(""","properties":{"source":""").append(jsonString(r.source))
        sb.append(""","source_id":""").append(jsonString(r.sourceId))
        sb.append(""","category":""").append(jsonString(r.category))
        sb.append(""","name":""").append(jsonString(r.name))
        if (r.region != null) sb.append(""","region":""").append(jsonString(r.region))
        if (r.unitName != null) sb.append(""","unit_name":""").append(jsonString(r.unitName))
        if (r.reserveUrl != null) sb.append(""","reserve_url":""").append(jsonString(r.reserveUrl))
        sb.append(""","raw":""").append(r.propertiesJson)
        sb.append("}}")
    }
    sb.append("]}")
    return sb.toString()
}

private fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

private fun escapeJsonInline(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
