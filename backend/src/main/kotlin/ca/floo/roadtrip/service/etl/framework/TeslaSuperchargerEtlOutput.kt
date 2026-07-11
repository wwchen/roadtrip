package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.models.domain.TeslaSuperchargerUpsertCandidate

data class TeslaSuperchargerEtlOutput(
    val superchargers: List<TeslaSuperchargerUpsertCandidate>,
)
