package ca.floo.roadtrip.service.etl.vendors.aspira

/**
 * Each geometry input knows how to extract (name → lat/lon) tuples from
 * its envelope shape and seed them into a shared index.
 */
sealed interface GeometrySource {
    fun indexInto(byName: MutableMap<String, Pair<Double, Double>>)
}
