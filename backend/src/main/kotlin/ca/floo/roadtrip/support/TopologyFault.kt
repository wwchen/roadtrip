package ca.floo.roadtrip.support

/** How GEOS names a self-intersection in the driver's cause chain. */
private const val TOPOLOGY_FAULT = "TopologyException"

/** One log line for both sites that serve empty on a topology fault. */
const val TOPOLOGY_FAULT_EMPTY_RESULT = "GEOS topology fault in a polygon query, returning empty: {}"

/**
 * The messages PostGIS's `ST_GeomFromGeoJSON` emits for input that is not a
 * structurally valid geometry, as opposed to a valid-but-self-intersecting one.
 * Confirmed against PostGIS 3.4 (`postgis_full_version()`); a ring with too few
 * points or an unclosed ring is *not* one of these — GEOS accepts and silently
 * degenerates those, so they are not listed here.
 */
private val geometryParseFaultMessages =
    listOf(
        "are not an array",
        "are not sufficiently nested",
        "Too few ordinates in GeoJSON",
        "invalid GeoJson representation",
    )

/**
 * A GEOS self-intersection in one corridor polygon is a bad shape, not an
 * outage. Typed as [Throwable] so the repo (which catches jOOQ's own exception)
 * and the service (which may not name jOOQ) share one marker.
 */
fun isTopologyFault(e: Throwable): Boolean = causeChain(e).contains(TOPOLOGY_FAULT)

/**
 * A boundary that is not a structurally valid GeoJSON geometry (too few ring
 * points, an unclosed ring, an unrecognized type) rather than a valid shape
 * GEOS can't process. Distinct from [isTopologyFault]: this is a client error.
 */
fun isGeometryParseFault(e: Throwable): Boolean {
    val chain = causeChain(e)
    return geometryParseFaultMessages.any { chain.contains(it) }
}
