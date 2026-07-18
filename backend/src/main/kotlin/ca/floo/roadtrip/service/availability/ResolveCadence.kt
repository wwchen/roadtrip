package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo

private const val GLOBAL_DEFAULT_SEC = 300

/**
 * Resolves the poller's cadence as the tightest (min) over live watches, where
 * each watch resolves the spec's three-level fall-through:
 * `watch.cadence_sec ?? poi.cadence_override_sec ?? GLOBAL_DEFAULT_SEC`.
 *
 * A watch's `cadenceSec` is a NULLABLE desired override: NULL means "no
 * watch-level preference," so the rung falls through to the POI override, then
 * the global default. [poiCadenceOverrideSec] is the override of the poller's
 * *representative* POI — a poller has one cadence and one representative POI, so
 * the override is a single per-poller rung rather than a per-watch-target lookup.
 */
internal fun resolveCadenceSec(
    liveWatches: List<AvailabilityWatchRepo.Watch>,
    poiCadenceOverrideSec: Int?,
): Int {
    val resolved =
        liveWatches.map { w ->
            w.cadenceSec
                ?: poiCadenceOverrideSec
                ?: GLOBAL_DEFAULT_SEC
        }
    return resolved.minOrNull() ?: GLOBAL_DEFAULT_SEC
}
