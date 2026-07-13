package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process, per-provider cooldown state.
 *
 * When a provider errors out we don't want to keep hammering it: [recordFailure]
 * stamps an expiry, [isCooling] reports whether the cooldown is still active, and
 * [sortHealthyFirst] demotes cooling providers to the tail of a candidate list
 * (without dropping them — a sole cooling candidate is still worth trying rather
 * than returning "no data").
 *
 * State is kept in a [ConcurrentHashMap] so recordings and reads across
 * concurrent poll executors don't need external synchronization; expiry checks
 * use `remove(key, expected)` for compare-and-delete on lazy eviction. Actual
 * wiring (into [FailoverAvailabilityFetcher]) is done outside this class.
 */
internal class ProviderCooldownTracker(
    private val cooldown: Duration,
    private val clock: () -> Instant = Instant::now,
) {
    private val expiries = ConcurrentHashMap<AvailabilityProviderId, Instant>()

    fun recordFailure(id: AvailabilityProviderId) {
        expiries[id] = clock().plus(cooldown)
    }

    fun recordSuccess(id: AvailabilityProviderId) {
        expiries.remove(id)
    }

    fun isCooling(id: AvailabilityProviderId): Boolean {
        val expiry = expiries[id] ?: return false
        if (!expiry.isAfter(clock())) {
            // Compare-and-delete: only remove if the entry we saw is still the
            // one in the map. If another thread recorded a fresh failure in the
            // meantime, keep that new state.
            expiries.remove(id, expiry)
            return false
        }
        return true
    }

    /**
     * Returns [items] with cooling providers moved to the tail. Kotlin's
     * [Iterable.sortedWith] is a stable sort, so the relative order within each
     * cohort (healthy or cooling) is preserved. Cooling providers are demoted,
     * never excluded — a sole candidate that happens to be cooling is still
     * worth trying rather than returning nothing.
     */
    fun <T> sortHealthyFirst(
        items: List<T>,
        idOf: (T) -> AvailabilityProviderId,
    ): List<T> = items.sortedWith(compareBy { if (isCooling(idOf(it))) 1 else 0 })
}
