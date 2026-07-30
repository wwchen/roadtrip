package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo

/**
 * Resolves the poller's cadence as the tightest (min) over live watches, where
 * each watch resolves the spec's three-level fall-through:
 * `watch.cadence_sec ?? poi.cadence_override_sec ?? globalDefaultSec`.
 *
 * A watch's `cadenceSec` is a NULLABLE desired override: NULL means "no
 * watch-level preference," so the rung falls through to the POI override, then
 * the global default. [poiCadenceOverrideSec] is the override of the poller's
 * *representative* POI — a poller has one cadence and one representative POI, so
 * the override is a single per-poller rung rather than a per-watch-target lookup.
 *
 * [globalDefaultSec] is the configured global rung
 * (`roadtrip.availability.poller.default-cadence`), passed in rather than read
 * here so cadence resolution stays a pure function of its inputs.
 */
internal fun resolveCadenceSec(
    liveWatches: List<AvailabilityWatchRepo.Watch>,
    poiCadenceOverrideSec: Int?,
    globalDefaultSec: Int,
): Int {
    val resolved =
        liveWatches.map { w ->
            w.cadenceSec
                ?: poiCadenceOverrideSec
                ?: globalDefaultSec
        }
    return resolved.minOrNull() ?: globalDefaultSec
}
