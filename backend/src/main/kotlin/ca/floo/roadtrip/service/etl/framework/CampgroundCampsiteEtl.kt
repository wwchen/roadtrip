package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.etl.CampgroundCampsiteEtlOutput

/**
 * ETL that produces both campgrounds and campsites in a single pass.
 *
 * Most campground data providers naturally emit both levels: a
 * campground list plus the individual sites within each. Implement
 * this interface when the provider's raw data carries both and
 * splitting them into separate ETL chains would force redundant
 * parsing or lossy intermediate serialization. The orchestrator
 * persists campgrounds first, then campsites.
 */
interface CampgroundCampsiteEtl<DTO> : SourceEtl<DTO, CampgroundCampsiteEtlOutput>
