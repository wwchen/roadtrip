package ca.floo.roadtrip.importer

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

// Build a minimal GeoJSON Point string. The Importer wraps this in
// ST_SetSRID(ST_GeomFromGeoJSON(?), 4326) so SRID is applied at insert time.
fun pointGeoJson(lon: Double, lat: Double): String =
    """{"type":"Point","coordinates":[$lon,$lat]}"""

// Pass an already-Polygon/MultiPolygon JsonObject through unchanged.
fun geometryGeoJson(geom: JsonObject): String = geom.toString()

// Collapse anything outside [a-z0-9:_-] to '_' so the source_id CHECK
// regex passes. Trailing/leading underscores are trimmed.
fun String.toSlug(): String =
    lowercase().replace(Regex("[^a-z0-9:_-]+"), "_").trim('_')

// Stable 8-hex hash from a seed string. Used by sources where the
// upstream identifier collides between rows; (slug, hash8) keeps source_id
// unique across re-runs.
fun stableHash8(seed: String): String {
    val md = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
    return md.take(4).joinToString("") { "%02x".format(it) }
}

internal fun JsonElement.contentOrNull(): String? =
    runCatching { jsonPrimitive.content }.getOrNull()
