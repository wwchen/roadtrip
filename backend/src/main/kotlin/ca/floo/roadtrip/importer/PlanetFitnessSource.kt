package ca.floo.roadtrip.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

// data/planet-fitness.geojson is OSM-derived. osm_id is "node/<digits>" and
// is stable-ish (OSM rarely renumbers nodes), so we use it directly as
// source_id with sanitization. Only ~26% have a website, but reserve_url
// stays null per the prior design call to drop PF deeplinks.
class PlanetFitnessSource(
    private val file: File,
) : Source {
    override val name = "osm-pf"

    override fun staged(): Sequence<StagedPoi> =
        sequence {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val features = root["features"]!!.jsonArray
            val fetchedAt = readFetchedAt(root, file)
            for (feat in features) {
                val obj = feat.jsonObject
                val geom = obj["geometry"]?.jsonObject ?: continue
                if (geom["type"]?.jsonPrimitive?.content != "Point") continue
                val coords = geom["coordinates"]?.jsonArray ?: continue
                val lon = coords[0].jsonPrimitive.content.toDouble()
                val lat = coords[1].jsonPrimitive.content.toDouble()
                val props = obj["properties"]?.jsonObject ?: continue
                val osmId = props["osm_id"]?.jsonPrimitive?.contentOrNull() ?: continue
                val n = props["name"]?.jsonPrimitive?.contentOrNull() ?: "Planet Fitness"
                yield(
                    StagedPoi(
                        sourceId = osmId.toSlug(),
                        category = Category.PLANET_FITNESS,
                        name = n,
                        geomGeoJson = pointGeoJson(lon, lat),
                        region = props["state"]?.jsonPrimitive?.contentOrNull(),
                        unitName = null,
                        properties = props,
                        reserveUrl = null,
                        fetchedAt = fetchedAt,
                    ),
                )
            }
        }
}
