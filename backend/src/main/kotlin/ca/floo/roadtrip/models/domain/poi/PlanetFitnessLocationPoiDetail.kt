package ca.floo.roadtrip.models.domain.poi

import ca.floo.roadtrip.models.domain.PlanetFitnessLocation

/**
 * Planet Fitness-owned projection for hydrating GET /api/pois/{id}.
 */
data class PlanetFitnessLocationPoiDetail(
    val location: PlanetFitnessLocation,
    val propertiesJson: String,
)
