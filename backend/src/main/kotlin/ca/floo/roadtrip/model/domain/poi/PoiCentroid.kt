package ca.floo.roadtrip.model.domain.poi

/**
 * A POI's representative interior point (`ST_PointOnSurface`), used to place a
 * POI in a time zone.
 *
 * The two nullable coordinates are not the same thing as a null [PoiCentroid]:
 * a null centroid means there is no active POI row at all, while null
 * coordinates mean the row exists but has no usable geometry — callers that
 * must 404 can distinguish the two.
 */
data class PoiCentroid(
    val lat: Double?,
    val lng: Double?,
)
