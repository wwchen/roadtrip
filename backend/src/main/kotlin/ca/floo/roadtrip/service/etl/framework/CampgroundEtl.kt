package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.etl.CampgroundEtlOutput

interface CampgroundEtl<DTO> : SourceEtl<DTO, CampgroundEtlOutput>
