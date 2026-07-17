package ca.floo.roadtrip.model.domain.poi

import ca.floo.roadtrip.model.domain.TeslaSupercharger

/**
 * Tesla Supercharger-owned projection for hydrating GET /api/pois/{id}.
 */
data class TeslaSuperchargerPoiDetail(
    val supercharger: TeslaSupercharger,
    val propertiesJson: String,
)
