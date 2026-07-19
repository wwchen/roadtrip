package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.etl.CampsiteEtlOutput

interface CampsiteEtl<DTO> : SourceEtl<DTO, CampsiteEtlOutput>
