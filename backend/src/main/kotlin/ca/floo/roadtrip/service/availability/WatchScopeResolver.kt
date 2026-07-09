package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.domain.Campsite
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
     * watch's shared `reservableFilters`. Union across all targets,
     * first-seen order preserved — this is the entire seam
     * [AvailabilityPollerMembership.sync] depends on, unchanged since PR1.
     */
    fun resolve(watch: AvailabilityWatchRepo.Watch): List<Campsite> {
        val seen = LinkedHashMap<Long, Campsite>()
        for (target in watch.targets) {
            val resolved =
                target.reservableId?.let { id -> campsitesRepo.findById(id)?.let(::listOf) ?: emptyList() }
                    ?: target.poiId?.let { poiId -> resolvePoi(poiId, watch.reservableFilters) }
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
        val loops = collectStringFilter(filters, "loop")
        val siteTypes = collectStringFilter(filters, "site_type")
        return all.filter { r ->
            (loops.isEmpty() || (r.loop != null && loops.contains(r.loop))) &&
                (siteTypes.isEmpty() || (r.siteType != null && siteTypes.contains(r.siteType)))
        }
    }
}

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
