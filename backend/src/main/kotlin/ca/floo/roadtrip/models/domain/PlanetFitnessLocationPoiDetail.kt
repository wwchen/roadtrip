package ca.floo.roadtrip.models.domain

/**
 * Planet Fitness-owned projection for hydrating GET /api/pois/{id}.
 */
data class PlanetFitnessLocationPoiDetail(
    val location: PlanetFitnessLocation,
    val propertiesJson: String,
)
