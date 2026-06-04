package ca.floo.roadtrip.importer

import kotlinx.serialization.json.JsonObject

// One row staged for upsert. geomGeoJson is a GeoJSON geometry string —
// "{\"type\":\"Point\",\"coordinates\":[lon,lat]}" for points, or the full
// Polygon/MultiPolygon for state/national park shapes. The importer wraps
// it in ST_SetSRID(ST_GeomFromGeoJSON(?), 4326) at INSERT time so the
// SRID is consistent regardless of what the upstream JSON declared.
data class StagedPoi(
    val sourceId: String, // unique within source; matches ^[a-z0-9:_-]+$
    val category: Category,
    val name: String,
    val geomGeoJson: String,
    val region: String?, // US state / Canadian province
    val unitName: String?, // containing park / forest, if any
    val properties: JsonObject,
    val reserveUrl: String?,
    val fetchedAt: java.time.Instant,
)

enum class Category(
    val sql: String,
) {
    CAMPGROUND("campground"),
    STATE_PARK("state-park"),
    NATIONAL_PARK("national-park"),
    PLANET_FITNESS("planet-fitness"),
}

// A Source pulls raw data (from a file or HTTP endpoint), normalizes it into
// StagedPoi rows, and yields them. The importer is responsible for opening
// an import_runs row and applying mark-and-sweep semantics — Source is pure
// extract+transform.
interface Source {
    val name: String // matches the source column in pois

    fun staged(): Sequence<StagedPoi>
}
