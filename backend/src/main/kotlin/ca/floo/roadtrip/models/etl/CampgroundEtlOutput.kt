package ca.floo.roadtrip.models.etl

import ca.floo.roadtrip.models.domain.CampgroundUpsertCandidate

data class CampgroundEtlOutput(
    val campgrounds: List<CampgroundUpsertCandidate>,
)
