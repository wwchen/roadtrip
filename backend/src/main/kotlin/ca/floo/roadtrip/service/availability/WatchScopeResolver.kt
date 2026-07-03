package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableType
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.ReservableRepo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class WatchScopeResolver(
    private val reservablesRepo: ReservableRepo,
) {
    fun resolve(watch: AvailabilityWatchRepo.Watch): List<Reservable> {
        watch.reservableId?.let { id ->
            return reservablesRepo.findById(id)?.let(::listOf) ?: emptyList()
        }
        return resolvePoi(watch.poiId ?: return emptyList(), watch.reservableFilters)
    }

    private fun resolvePoi(
        poiId: Long,
        filters: JsonObject,
    ): List<Reservable> {
        val all = reservablesRepo.findByPoi(poiId, type = ReservableType.SITE)
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
