package ca.floo.roadtrip.model.etl

import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate

/**
 * Canonical output for vendor campsite catalog ETLs.
 *
 * Vendor adapters emit these DTOs instead of the retired wide `pois` and
 * the retired reservables-table contracts. The orchestrator persists terminal outputs through
 * typed catalog tables and lean POI wrappers.
 */
data class CampsiteEtlOutput(
    val campsites: List<CampsiteUpsertCandidate>,
)
