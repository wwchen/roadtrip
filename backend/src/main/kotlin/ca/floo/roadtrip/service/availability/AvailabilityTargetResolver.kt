package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.repo.AvailabilityPollerRepo

/**
 * Resolves an already-loaded [CampsiteAvailabilityTarget] to the provider adapter, parent
 * provider ref, and date context needed to fetch its availability. A port so
 * the request path can be unit-tested with an in-memory fake;
 * [DbAvailabilityTargetResolver] is the production, DB-backed implementation.
 */
internal interface AvailabilityTargetResolver {
    /** Resolve an already-loaded campsite, or null when it has no resolvable
     *  availability provider. */
    fun resolve(campsite: CampsiteAvailabilityTarget): ResolvedAvailabilityTarget?

    /** Resolve a poller to its full fetch plan, or null when the poller has no live watches. */
    fun resolve(poller: AvailabilityPollerRepo.Poller): PollerFetchPlan?
}
