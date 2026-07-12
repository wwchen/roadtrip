package ca.floo.roadtrip.models.etl

import ca.floo.roadtrip.models.domain.PlanetFitnessLocationUpsertCandidate

data class PlanetFitnessLocationEtlOutput(
    val locations: List<PlanetFitnessLocationUpsertCandidate>,
)
