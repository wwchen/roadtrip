package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.models.domain.PlanetFitnessLocationUpsertCandidate

data class PlanetFitnessLocationEtlOutput(
    val locations: List<PlanetFitnessLocationUpsertCandidate>,
)
