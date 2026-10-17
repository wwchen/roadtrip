package ca.floo.roadtrip.service.availability

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** The cells a request needs: every campsite against every date, deduplicated. */
fun requiredCells(
    campsiteIds: Collection<Long>,
    dates: Collection<LocalDate>,
): Set<AvailabilityCell> =
    campsiteIds
        .distinct()
        .flatMapTo(mutableSetOf()) { campsiteId -> dates.map { AvailabilityCell(campsiteId, it) } }

/**
 * Of [required], the cells no stored observation answers at or after
 * [freshAtOrAfter] — a cell with no observation and a cell with a stale one are
 * both unanswered. Empty means the request can be served from the store.
 *
 * Deliberately a set and not a boolean: the caller wants to know *which* cells
 * are missing, not just that some are.
 */
fun unansweredCells(
    required: Set<AvailabilityCell>,
    observedAtByCell: Map<AvailabilityCell, Instant>,
    freshAtOrAfter: Instant,
): Set<AvailabilityCell> =
    required.filterNotTo(mutableSetOf()) { cell ->
        observedAtByCell[cell]?.isFreshAsOf(freshAtOrAfter) == true
    }

/** Fresh when every observation was seen at or after [freshAtOrAfter]. Empty = vacuously fresh. */
fun isFreshAsOf(
    observedAts: List<Instant>,
    freshAtOrAfter: Instant,
): Boolean = observedAts.all { it.isFreshAsOf(freshAtOrAfter) }

/** Fresh when every observation was seen within [ttl] of [now]. Empty = vacuously fresh. */
fun isFresh(
    observedAts: List<Instant>,
    now: Instant,
    ttl: Duration,
): Boolean = isFreshAsOf(observedAts, now.minus(ttl))

private fun Instant.isFreshAsOf(freshAtOrAfter: Instant): Boolean = !isBefore(freshAtOrAfter)
