package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val CENTROID_NAME_ATTRIBUTE = "DESC_EN"

/**
 * ArcGIS centroid-mode JSON: features with `attributes.DESC_EN` and
 * `centroid: { x, y }`. Parks Canada's Places layer is what reads through it
 * today; names like "Banff National Park of Canada" collapse through
 * [normalize] to the bare name a leaf carries.
 */
class ArcGisCentroidSource(
    private val envelopes: List<Envelope>,
) : GeometrySource {
    override fun points(): Sequence<NamedPoint> =
        sequence {
            for (env in envelopes) {
                val feats = env.payload.jsonObject["features"]?.jsonArray ?: continue
                for (f in feats) {
                    val o = f.jsonObject
                    val attrs = o["attributes"]?.jsonObject ?: continue
                    val name = attrs[CENTROID_NAME_ATTRIBUTE]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                    val centroid = o["centroid"]?.jsonObject ?: continue
                    val lon = centroid["x"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                    val lat = centroid["y"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                    yield(NamedPoint(name, lat, lon))
                }
            }
        }
}
