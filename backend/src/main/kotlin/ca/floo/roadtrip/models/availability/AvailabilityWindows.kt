package ca.floo.roadtrip.models.availability

/**
 * The two windows a single availability fetch works with. [target] is the
 * caller's logical day range (what drives cache coverage and returned slices).
 * [fetch] is the provider-shaped bucket range we ask upstream for and record.
 * It may be wider than [target] so adjacent day-grid reads reuse the same
 * cached observations.
 */
internal data class AvailabilityWindows(
    val target: ResolvedDateWindow,
    val fetch: ResolvedDateWindow,
)
