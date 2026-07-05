package ca.floo.roadtrip.service.availability

import java.time.Duration
import java.time.Instant

/** Full coverage: exactly one row per (target, date) in the requested window. */
fun hasFullCoverage(
    targetCount: Int,
    dateCount: Int,
    rowCount: Int,
): Boolean = rowCount == targetCount * dateCount

/** Fresh when every observation was seen within [ttl] of [now]. Empty = vacuously fresh. */
fun isFresh(
    observedAts: List<Instant>,
    now: Instant,
    ttl: Duration,
): Boolean {
    val freshAfter = now.minus(ttl)
    return observedAts.all { !it.isBefore(freshAfter) }
}
