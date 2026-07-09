package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import java.time.OffsetDateTime

/**
 * Watch->poller link maintenance. Called whenever a watch is written
 * (created, updated, paused/resumed) so poller coalescing happens at
 * watch-write time rather than needing a separate reconciliation pass.
 *
 * Resolves the watch's campsite set to its vendor call units (distinct
 * (provider, parentRefKey) pairs) and reconciles `availability_watch_poller`
 * to exactly that set, creating/reviving pollers as needed via
 * [AvailabilityPollerRepo.upsertActive]. Pollers are the coalesced unit:
 * many watches on the same parent campground share one poller row.
 */
internal class AvailabilityPollerMembership(
    private val scopeResolver: WatchScopeResolver,
    private val targets: AvailabilityTargetResolver,
) {
    /**
     * Recomputes [watch]'s poller links. A non-ACTIVE watch (paused, done)
     * holds no links — its links are cleared and any poller left without
     * links is deactivated. An ACTIVE watch's campsite set is resolved to
     * targets, deduped to one representative poi per distinct
     * (provider, parentRefKey), upserted into a poller each, and the
     * watch's links replaced with exactly that set.
     *
     * [tighterCadencePull] is forwarded to [AvailabilityPollerRepo.upsertActive]
     * so a newly-linked watch with a faster cadence can pull an existing
     * poller's `next_run_at` earlier; pass null to leave scheduling alone.
     */
    fun sync(
        watch: AvailabilityWatchRepo.Watch,
        repo: AvailabilityPollerRepo,
        tighterCadencePull: OffsetDateTime?,
    ) {
        if (watch.status != WatchStatus.ACTIVE) {
            repo.replaceLinksForWatch(watch.id, emptySet())
            repo.deactivatePollersWithNoLinks()
            return
        }

        val resolved = scopeResolver.resolve(watch).mapNotNull { targets.resolve(it) }

        // (provider, parentRefKey) -> representative poi id. LinkedHashMap so
        // the first target seen for a key wins deterministically.
        val keyToPoi = LinkedHashMap<Pair<String, String>, Long>()
        for (target in resolved) {
            val key =
                target.provider.id.name
                    .lowercase() to parentRefKey(target.parentRef)
            keyToPoi.putIfAbsent(key, target.parentPoiId)
        }

        val pollerIds =
            keyToPoi
                .map { (key, poiId) ->
                    repo.upsertActive(
                        provider = key.first,
                        parentRef = key.second,
                        poiId = poiId,
                        pullNextRunAt = tighterCadencePull,
                    )
                }.toSet()

        repo.replaceLinksForWatch(watch.id, pollerIds)
        repo.deactivatePollersWithNoLinks() // eager reap of any now-orphaned poller
    }
}
