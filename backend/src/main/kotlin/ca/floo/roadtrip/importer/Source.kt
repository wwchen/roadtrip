package ca.floo.roadtrip.importer

import kotlinx.serialization.json.JsonObject

// One row staged for upsert. The source produces these; the importer's
// mark-and-sweep loop persists them. geomWkt must be EPSG:4326 WKT — for
// Points this is "POINT(lon lat)", for state/national parks Polygon or
// MultiPolygon as appropriate.
data class StagedPoi(
    val sourceId: String,            // unique within source; matches ^[a-z0-9:_-]+$
    val category: Category,
    val name: String,
    val geomWkt: String,
    val region: String?,             // US state / Canadian province
    val unitName: String?,           // containing park / forest, if any
    val properties: JsonObject,
    val reserveUrl: String?,
    val fetchedAt: java.time.Instant,
)

enum class Category(val sql: String) {
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
    val name: String                 // matches the source column in pois
    fun staged(): Sequence<StagedPoi>
}
