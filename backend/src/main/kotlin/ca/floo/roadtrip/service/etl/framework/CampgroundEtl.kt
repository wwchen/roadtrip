package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate

interface CampgroundEtl<DTO> : SourceEtl<DTO, CampgroundUpsertCandidate>
