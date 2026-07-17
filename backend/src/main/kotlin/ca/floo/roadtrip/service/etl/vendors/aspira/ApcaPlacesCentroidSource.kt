package ca.floo.roadtrip.service.etl.vendors.aspira

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * APCA Places (national parks) — centroid-mode arcgis json, features with
 * `attributes.DESC_EN` and `centroid: { x, y }`. Names are like
 * "Banff National Park of Canada"; aggressive normalization in `normalize`
 * collapses the federal-park cruft so they can match the leaf's bare name.
 */
class ApcaPlacesCentroidSource(
    private val envelopes: List<ca.floo.roadtrip.model.metadata.Envelope>,
) : GeometrySource {
    override fun indexInto(byName: MutableMap<String, Pair<Double, Double>>) {
        for (env in envelopes) {
            val feats = env.payload.jsonObject["features"]?.jsonArray ?: continue
            for (f in feats) {
                val o = f.jsonObject
                val attrs = o["attributes"]?.jsonObject ?: continue
                val name = attrs["DESC_EN"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                val centroid = o["centroid"]?.jsonObject ?: continue
                val lon = centroid["x"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                val lat = centroid["y"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                val key = normalize(name)
                if (key.isNotEmpty()) byName.putIfAbsent(key, lat to lon)
            }
        }
    }
}
