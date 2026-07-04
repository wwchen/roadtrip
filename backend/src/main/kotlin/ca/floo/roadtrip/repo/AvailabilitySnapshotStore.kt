package ca.floo.roadtrip.repo

/**
 * Persistence port for per-day availability snapshots used by the
 * snapshot-backed read path ([ca.floo.roadtrip.service.api.SnapshotBackedAvailabilityService]).
 *
 * Implemented by the DB-backed [AvailabilitySnapshotRepo]; extracted so the
 * read path can be unit-tested with an in-memory fake instead of a live
 * Postgres. The snapshot DTOs stay nested on the repo — they describe the
 * persistence contract this port exposes.
 */
interface AvailabilitySnapshotStore {
    fun appendObservations(input: AvailabilitySnapshotRepo.SnapshotObservationBatch): Int

    /**
     * Delete snapshot history older than [cutoff] for the given reservables.
     * Scoped by reservable so it rides the (reservable_id, observed_at) index
     * and stays cheap when called opportunistically from the poll loop. The
     * live read path serves latest state from the cube, so pruning old history
     * never affects read latency — it only bounds the append-only log.
     * Returns rows deleted.
     */
    fun pruneObservationsBefore(
        reservableIds: List<Long>,
        cutoff: java.time.Instant,
    ): Int
}
