package ca.floo.roadtrip.model.etl

import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate

data class CampgroundCampsiteEtlOutput(
    val campgrounds: List<CampgroundUpsertCandidate>,
    val campsites: List<CampsiteUpsertCandidate>,
)
