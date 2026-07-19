package ca.floo.roadtrip.model.etl

import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate

data class CampgroundEtlOutput(
    val campgrounds: List<CampgroundUpsertCandidate>,
)
