package ca.floo.roadtrip.model.etl

import ca.floo.roadtrip.model.domain.PlanetFitnessLocationUpsertCandidate

data class PlanetFitnessLocationEtlOutput(
    val locations: List<PlanetFitnessLocationUpsertCandidate>,
)
