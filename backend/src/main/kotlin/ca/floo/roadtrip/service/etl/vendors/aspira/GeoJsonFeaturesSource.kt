package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val POINT_GEOMETRY_TYPE = "Point"
private const val LONGITUDE_INDEX = 0
private const val LATITUDE_INDEX = 1
private const val MIN_COORDINATES = 2

/** Tried in order when a source spec declares no `name_property`. */
@Suppress("TopLevelPropertyNaming")
private val DEFAULT_NAME_PROPERTIES = listOf("name", "Name")

/**
 * A GeoJSON FeatureCollection whose features carry Point geometry.
 * [nameProperty] names the attribute holding the feature name — Parks Canada's
 * Accommodation layer uses `Name_e` — and null falls back to
 * [DEFAULT_NAME_PROPERTIES].
 */
class GeoJsonFeaturesSource(
    private val envelopes: List<Envelope>,
    private val nameProperty: String? = null,
) : GeometrySource {
    private val nameKeys: List<String> = nameProperty?.let(::listOf) ?: DEFAULT_NAME_PROPERTIES

    override fun points(): Sequence<NamedPoint> =
        sequence {
            for (env in envelopes) {
                val feats = env.payload.jsonObject["features"]?.jsonArray ?: continue
                for (f in feats) {
                    val o = f.jsonObject
                    val props = o["properties"]?.jsonObject ?: continue
                    val name =
                        nameKeys.firstNotNullOfOrNull { key ->
                            props[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        } ?: continue
                    val geom = o["geometry"]?.jsonObject ?: continue
                    if (geom["type"]?.jsonPrimitive?.contentOrNull != POINT_GEOMETRY_TYPE) continue
                    val coords = geom["coordinates"]?.jsonArray ?: continue
                    if (coords.size < MIN_COORDINATES) continue
                    val lon = coords[LONGITUDE_INDEX].jsonPrimitive.contentOrNull?.toDoubleOrNull() ?: continue
                    val lat = coords[LATITUDE_INDEX].jsonPrimitive.contentOrNull?.toDoubleOrNull() ?: continue
                    yield(NamedPoint(name, lat, lon))
                }
            }
        }
}
