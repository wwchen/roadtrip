package ca.floo.roadtrip.repo

import java.time.LocalDate

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

    fun loadLatestObservations(
        reservableIds: List<Long>,
        dates: List<LocalDate>,
    ): List<AvailabilitySnapshotRepo.LatestObservation>
}
