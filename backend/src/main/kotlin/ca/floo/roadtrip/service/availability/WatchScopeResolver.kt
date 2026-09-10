package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class WatchScopeResolver(
    private val campsitesRepo: CampsiteRepo,
) {
    /**
     * Resolves a watch's full target SET to the flat, de-duplicated list of
     * campsites it covers. A campsite target resolves to itself; a POI
     * target expands to that POI's site-type children, filtered by the
     * watch's shared `campsiteFilters`. Union across all targets,
     * first-seen order preserved — this is the entire seam
     * [AvailabilityPollerMembership.sync] depends on.
     */
    fun resolve(watch: AvailabilityWatchRepo.Watch): List<Campsite> {
        val seen = LinkedHashMap<Long, Campsite>()
        for (target in watch.targets) {
            val resolved =
                target.campsiteId?.let { id -> campsitesRepo.findById(id)?.let(::listOf) ?: emptyList() }
                    ?: target.poiId?.let { poiId -> resolvePoi(poiId, watch.campsiteFilters) }
                    ?: emptyList()
            for (r in resolved) seen.putIfAbsent(r.id, r)
        }
        return seen.values.toList()
    }

    private fun resolvePoi(
        poiId: Long,
        filters: JsonObject,
    ): List<Campsite> {
        val all = campsitesRepo.findByPoi(poiId)
        val loops = collectStringFilter(filters, LOOP_FILTER_KEY)
        // A stored value outside the wire vocabulary drops out of `siteTypes` but
        // still counts as a filter, so such a watch keeps matching nothing.
        val storedSiteTypes = collectStringFilter(filters, SITE_TYPE_FILTER_KEY)
        val siteTypes = storedSiteTypes.mapNotNull(CampsiteKind::fromWire).toSet()
        return all.filter { r ->
            (loops.isEmpty() || (r.loopName != null && loops.contains(r.loopName))) &&
                (storedSiteTypes.isEmpty() || siteTypes.contains(r.kind))
        }
    }
}

private const val LOOP_FILTER_KEY = "loop"
private const val SITE_TYPE_FILTER_KEY = "site_type"

private fun collectStringFilter(
    filters: JsonObject,
    key: String,
): Set<String> {
    val value = filters[key] ?: return emptySet()
    return when (value) {
        is JsonPrimitive -> if (value.isString) setOf(value.content) else emptySet()
        is JsonArray ->
            value
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .toSet()
        else -> emptySet()
    }
}
