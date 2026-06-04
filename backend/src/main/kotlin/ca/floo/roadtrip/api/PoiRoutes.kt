package ca.floo.roadtrip.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.jooq.DSLContext

// Hard cap. The Mapbox/MapLibre frontend chokes on >5k features per source,
// and our POIs cover all of US/CA — the user is expected to zoom in. Returning
// truncated=true tells the client to ask the user to zoom further.
const val POI_LIMIT: Int = 2000

// /api/pois?bbox=west,south,east,north&category=campground,state-park,...
//
// Returns a GeoJSON FeatureCollection with truncated:true when the bbox spans
// more rows than POI_LIMIT. geom is materialized via ST_AsGeoJSON so polygons
// (state/national parks) and points (campgrounds/PF) come through with the
// correct GeoJSON type without any client-side conversion. properties carries
// the full source-side JSONB so the client can read amenities, season, etc.
fun Route.poiRoutes(ctx: DSLContext) {
    get("/api/pois") {
        val bboxParam = call.request.queryParameters["bbox"]
        val bbox = bboxParam?.let(::parseBbox)
        if (bbox == null) {
            call.respondText(
                """{"error":"missing or malformed bbox; expected west,south,east,north"}""",
                io.ktor.http.ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@get
        }

        val categories =
            call.request.queryParameters["category"]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }

        val rows = fetchPois(ctx, bbox, categories, POI_LIMIT + 1)
        val truncated = rows.size > POI_LIMIT
        val effective = if (truncated) rows.take(POI_LIMIT) else rows

        call.respondText(
            buildFeatureCollection(effective, truncated),
            io.ktor.http.ContentType.Application.Json,
        )
    }

    get("/api/pois/health") {
        call.respondText("""{"status":"ok"}""", io.ktor.http.ContentType.Application.Json)
    }
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
    limit: Int,
): List<PoiRow> {
    val sql =
        buildString {
            append(
                """
                SELECT id, source, source_id, category, name, region, unit_name,
                       reserve_url, ST_AsGeoJSON(geom) AS geom_json,
                       properties::text AS properties_text
                FROM pois
                WHERE deleted_at IS NULL
                  AND geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                """.trimIndent(),
            )
            if (categories != null) append("\n  AND category = ANY(?)")
            append("\nLIMIT ?")
        }

    val args = mutableListOf<Any>(bbox.west, bbox.south, bbox.east, bbox.north)
    if (categories != null) args.add(categories.toTypedArray())
    args.add(limit)

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
): String {
    // Hand-built JSON. Properties come in as a JSONB ::text and are merged
    // into the feature's properties object — building this with kotlinx
    // would require parsing+re-serializing the JSONB on every row, and the
    // bbox endpoint is hot.
    val sb = StringBuilder()
    sb
        .append("""{"type":"FeatureCollection","truncated":""")
        .append(truncated)
        .append(""","features":[""")
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
