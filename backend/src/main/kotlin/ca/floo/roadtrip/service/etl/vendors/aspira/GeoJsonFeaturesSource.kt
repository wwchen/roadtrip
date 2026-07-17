package ca.floo.roadtrip.service.etl.vendors.aspira

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Generic GeoJSON FeatureCollection with `properties.name` (best-effort fallback). */
class GeoJsonFeaturesSource(
    private val envelopes: List<ca.floo.roadtrip.model.metadata.Envelope>,
    private val slug: String,
) : GeometrySource {
    override fun indexInto(byName: MutableMap<String, Pair<Double, Double>>) {
        for (env in envelopes) {
            val feats = env.payload.jsonObject["features"]?.jsonArray ?: continue
            for (f in feats) {
                val o = f.jsonObject
                val props = o["properties"]?.jsonObject ?: continue
                val name =
                    listOfNotNull(
                        props["name"]?.jsonPrimitive?.contentOrNull,
                        props["Name"]?.jsonPrimitive?.contentOrNull,
                    ).firstOrNull { it.isNotBlank() } ?: continue
                val geom = o["geometry"]?.jsonObject ?: continue
                if (geom["type"]?.jsonPrimitive?.contentOrNull != "Point") continue
                val coords = geom["coordinates"]?.jsonArray ?: continue
                if (coords.size < 2) continue
                val lon = coords[0].jsonPrimitive.contentOrNull?.toDoubleOrNull() ?: continue
                val lat = coords[1].jsonPrimitive.contentOrNull?.toDoubleOrNull() ?: continue
                val key = normalize(name)
                if (key.isNotEmpty()) byName.putIfAbsent(key, lat to lon)
            }
        }
    }
}
