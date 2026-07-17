package ca.floo.roadtrip.model.domain.poi

import ca.floo.roadtrip.model.domain.PlanetFitnessLocation

/**
 * Planet Fitness-owned projection for hydrating GET /api/pois/{id}.
 */
data class PlanetFitnessLocationPoiDetail(
    val location: PlanetFitnessLocation,
    val propertiesJson: String,
)
