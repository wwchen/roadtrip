package ca.floo.roadtrip.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant

// State + national parks ship as GeoJSON FeatureCollection with Polygon /
// MultiPolygon geometries. The geom column is geometry(Geometry,4326), so
// we pass the polygon JSON straight through to ST_GeomFromGeoJSON.
//
// US states encoded as State_Nm; park name in Unit_Nm. There's no obvious
// stable id per feature, so source_id = slug(name)-hash(name|state).
class ParksGeoJsonSource(
    private val file: File,
    override val name: String,
    private val category: Category,
) : Source {

    override fun staged(): Sequence<StagedPoi> = sequence {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val features = root["features"]!!.jsonArray
        val fetchedAt = Instant.ofEpochMilli(file.lastModified())
        for (feat in features) {
            val obj = feat.jsonObject
            val geom = obj["geometry"]?.jsonObject ?: continue
            val props = obj["properties"]?.jsonObject ?: continue
            val unitName = props["Unit_Nm"]?.jsonPrimitive?.content ?: continue
            val state = props["State_Nm"]?.jsonPrimitive?.content
            // Many parks have multiple management-unit polygons sharing
            // (Unit_Nm, State_Nm, Loc_Nm). Hashing the geometry too keeps
            // each polygon as its own row instead of collapsing them.
            val geomJson = geometryGeoJson(geom)
            val hash8 = stableHash8("$unitName|${state ?: ""}|${props["Loc_Nm"]?.contentOrNull() ?: ""}|$geomJson")
            yield(
                StagedPoi(
                    sourceId = "${unitName.toSlug()}-${(state ?: "us").toSlug()}-$hash8",
                    category = category,
                    name = unitName,
                    geomGeoJson = geomJson,
                    region = state,
                    unitName = unitName,
                    properties = props,
                    reserveUrl = null,
                    fetchedAt = fetchedAt,
                )
            )
        }
    }
}
