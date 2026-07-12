package ca.floo.roadtrip.models.etl

import ca.floo.roadtrip.models.domain.TeslaSuperchargerUpsertCandidate

data class TeslaSuperchargerEtlOutput(
    val superchargers: List<TeslaSuperchargerUpsertCandidate>,
)
