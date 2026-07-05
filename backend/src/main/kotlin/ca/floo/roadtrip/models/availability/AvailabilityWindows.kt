package ca.floo.roadtrip.models.availability

/**
 * The two windows a single availability fetch works with. [fetch] is the
 * widest window the vendor allows for one call (what we ask upstream and
 * record); [target] is the caller's requested window (what drives the cache
 * coverage check and the returned slice). The poller sets them equal; the
 * live read path sets [fetch] wider than [target] so paging is served from
 * the DB.
 */
internal data class AvailabilityWindows(
    val target: ResolvedDateWindow,
    val fetch: ResolvedDateWindow,
)
