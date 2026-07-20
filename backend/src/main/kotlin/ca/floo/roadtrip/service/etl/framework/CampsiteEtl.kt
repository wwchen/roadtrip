package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate

interface CampsiteEtl<DTO> : SourceEtl<DTO, CampsiteUpsertCandidate>
