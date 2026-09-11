package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.registry.GeometryFormat
import ca.floo.roadtrip.model.metadata.registry.GeometrySourceSpec

/** The one dispatch from a declared [GeometryFormat] to the parser that reads it. */
object GeometrySources {
    fun forSpec(
        spec: GeometrySourceSpec,
        envelopes: List<Envelope>,
    ): GeometrySource =
        when (spec.format) {
            GeometryFormat.USCAMPGROUNDS_CSV -> UsCampgroundsCsvSource(envelopes, spec.state)
            GeometryFormat.BCPARKS_STRAPI -> BcParksStrapiSource(envelopes)
            GeometryFormat.ARCGIS_CENTROIDS -> ArcGisCentroidSource(envelopes)
            GeometryFormat.GEOJSON_POINTS -> GeoJsonFeaturesSource(envelopes, spec.nameProperty)
        }
}
