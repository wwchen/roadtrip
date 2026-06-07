package ca.floo.roadtrip.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicReference

private val log = LoggerFactory.getLogger("SuperchargersRoutes")

// Polygon body cap. Mirrors PoiRoutes.MAX_POLYGON_VERTICES so corridors that
// /api/pois accepts are also accepted here.
private const val SC_MAX_POLYGON_VERTICES: Int = 2000

// POST /api/superchargers
//
// Request body (JSON):
//   {
//     "bbox": [west, south, east, north],            // required
//     "zoom": 8,                                     // optional; ignored
//     "polygon": {                                   // optional
//       "type": "Polygon",
//       "coordinates": [[[lng,lat], ...]]
//     }
//   }
//
// Returns a GeoJSON FeatureCollection of SC features inside the bbox (and
// inside the polygon when present). Same envelope shape as /api/pois — the
// frontend can treat them identically.
//
// SC data is still file-backed (pre-RFC-0005); the parsed feature list is
// cached in-process keyed by file mtime so we re-read only when the
// upstream fetcher rewrites the file.
fun Route.superchargersRoutes(dataDir: File) {
    val cache = ScCache(File(dataDir, "tesla-superchargers.geojson"))

    post("/api/superchargers") {
        val bodyText = call.receiveText()
        val req =
            try {
                parseScRequest(bodyText)
            } catch (e: Exception) {
                call.respondText(
                    """{"error":"bad_request","detail":"${escapeJson(e.message ?: "parse failed")}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

        val features = cache.features()
        if (features.isEmpty()) {
            call.respondText(
                """{"type":"FeatureCollection","features":[]}""",
                ContentType.Application.Json,
            )
            return@post
        }

        val polygonRings = req.polygonRings
        val sb = StringBuilder()
        sb.append("""{"type":"FeatureCollection"""")
        if (polygonRings != null) sb.append(""","corridor":true""")
        sb.append(""","features":[""")
        var emitted = 0
        for (f in features) {
            if (f.lng < req.bbox.west || f.lng > req.bbox.east) continue
            if (f.lat < req.bbox.south || f.lat > req.bbox.north) continue
            if (polygonRings != null && !pointInPolygon(f.lng, f.lat, polygonRings)) continue
            if (emitted > 0) sb.append(',')
            sb.append(f.json)
            emitted++
        }
        sb.append("]}")

        call.respondText(sb.toString(), ContentType.Application.Json)
    }
}

private data class ScRequest(
    val bbox: Bbox,
    val polygonRings: List<List<DoubleArray>>?, // outer + holes; each ring is [[lng,lat], ...]
)

private fun parseScRequest(bodyText: String): ScRequest {
    val root = Json.parseToJsonElement(bodyText).jsonObject

    val bboxArr = root["bbox"]?.jsonArray ?: error("missing bbox")
    require(bboxArr.size == 4) { "bbox must be [west,south,east,north]" }
    val nums = bboxArr.map { it.jsonPrimitive.doubleOrNull ?: error("bbox values must be numbers") }
    val (w, s, e, n) = nums
    require(w in -180.0..180.0 && e in -180.0..180.0) { "bbox lng out of range" }
    require(s in -90.0..90.0 && n in -90.0..90.0) { "bbox lat out of range" }
    require(w < e && s < n) { "bbox: west must be < east, south < north" }
    val bbox = Bbox(w, s, e, n)

    val polygonRings =
        root["polygon"]?.jsonObject?.let { obj ->
            val type = obj["type"]?.jsonPrimitive?.content
            require(type == "Polygon") { "polygon.type must be 'Polygon', got '$type'" }
            val ringsArr = obj["coordinates"]?.jsonArray ?: error("polygon.coordinates required")
            require(ringsArr.isNotEmpty()) { "polygon must have at least one ring" }
            val totalVerts = ringsArr.sumOf { it.jsonArray.size }
            require(totalVerts <= SC_MAX_POLYGON_VERTICES) {
                "polygon too large: $totalVerts vertices (max $SC_MAX_POLYGON_VERTICES). Simplify on the client."
            }
            ringsArr.map { ring ->
                val pts = ring.jsonArray
                require(pts.size >= 4) { "polygon ring must have >= 4 points" }
                pts.map { p ->
                    val arr = p.jsonArray
                    doubleArrayOf(
                        arr[0].jsonPrimitive.doubleOrNull ?: error("polygon coord not numeric"),
                        arr[1].jsonPrimitive.doubleOrNull ?: error("polygon coord not numeric"),
                    )
                }
            }
        }

    return ScRequest(bbox, polygonRings)
}

// Ray-casting point-in-polygon. Outer ring includes; subsequent rings (holes)
// exclude. Same convention as GeoJSON. Edge cases (point exactly on edge)
// are not specially handled — corridor edges are arbitrary smoothed lines,
// the precision of "exactly on edge" doesn't matter for SC visibility.
internal fun pointInPolygon(
    lng: Double,
    lat: Double,
    rings: List<List<DoubleArray>>,
): Boolean {
    if (rings.isEmpty()) return false
    if (!ringContains(lng, lat, rings[0])) return false
    for (i in 1 until rings.size) {
        if (ringContains(lng, lat, rings[i])) return false
    }
    return true
}

private fun ringContains(
    lng: Double,
    lat: Double,
    ring: List<DoubleArray>,
): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val xi = ring[i][0]
        val yi = ring[i][1]
        val xj = ring[j][0]
        val yj = ring[j][1]
        val intersect =
            (yi > lat) != (yj > lat) &&
                lng < (xj - xi) * (lat - yi) / (yj - yi + 0.0) + xi
        if (intersect) inside = !inside
        j = i
    }
    return inside
}

private data class ScFeature(
    val lng: Double,
    val lat: Double,
    val json: String, // pre-serialized "{...}" feature
)

private class ScCache(
    private val file: File,
) {
    private data class Snapshot(
        val mtime: Long,
        val features: List<ScFeature>,
    )

    private val ref = AtomicReference<Snapshot?>(null)

    fun features(): List<ScFeature> {
        if (!file.isFile) return emptyList()
        val mtime = file.lastModified()
        val cur = ref.get()
        if (cur != null && cur.mtime == mtime) return cur.features
        val parsed = parse(file)
        ref.set(Snapshot(mtime, parsed))
        return parsed
    }

    private fun parse(f: File): List<ScFeature> {
        return try {
            val root = Json.parseToJsonElement(f.readText()).jsonObject
            val features = root["features"]?.jsonArray ?: return emptyList()
            features.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val geom = obj["geometry"]?.jsonObject ?: return@mapNotNull null
                if (geom["type"]?.jsonPrimitive?.content != "Point") return@mapNotNull null
                val coords = geom["coordinates"]?.jsonArray ?: return@mapNotNull null
                if (coords.size < 2) return@mapNotNull null
                val lng = coords[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                val lat = coords[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                // Lift properties.id to top-level id. MapLibre's feature-state
                // and `promoteId`-style code paths read the top-level id; the
                // upstream Tesla feed only puts id in properties, so without
                // this copy the layer's paint expressions can resolve to null.
                val rebuilt = withTopLevelId(obj)
                ScFeature(lng, lat, rebuilt.toString())
            }
        } catch (e: Exception) {
            log.warn("failed to parse {}: {}", f, e.message)
            emptyList()
        }
    }

    private fun withTopLevelId(obj: JsonObject): JsonObject {
        if (obj["id"] != null) return obj
        val propId = obj["properties"]?.jsonObject?.get("id") ?: return obj
        return JsonObject(obj.toMutableMap().apply { put("id", propId) })
    }
}

private fun escapeJson(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
