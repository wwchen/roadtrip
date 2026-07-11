package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.models.domain.CampgroundUpsertCandidate

data class CampgroundEtlOutput(
    val campgrounds: List<CampgroundUpsertCandidate>,
)
