package ca.floo.roadtrip.support

/** How GEOS names a self-intersection in the driver's cause chain. */
private const val TOPOLOGY_FAULT = "TopologyException"

/** One log line for both sites that serve empty on a topology fault. */
const val TOPOLOGY_FAULT_EMPTY_RESULT = "on-route GEOS topology fault, returning empty: {}"

/**
 * A GEOS self-intersection in one corridor polygon is a bad shape, not an
 * outage. Typed as [Throwable] so the repo (which catches jOOQ's own exception)
 * and the service (which may not name jOOQ) share one marker.
 */
fun isTopologyFault(e: Throwable): Boolean = causeChain(e).contains(TOPOLOGY_FAULT)
