package ca.floo.roadtrip.model.etl

import ca.floo.roadtrip.model.domain.TeslaSuperchargerUpsertCandidate

data class TeslaSuperchargerEtlOutput(
    val superchargers: List<TeslaSuperchargerUpsertCandidate>,
)
