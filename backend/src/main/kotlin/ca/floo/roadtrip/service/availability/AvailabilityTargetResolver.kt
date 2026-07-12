package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.domain.CampsiteAvailabilityTarget

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
}
