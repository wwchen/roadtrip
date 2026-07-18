package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.repo.CampsiteParentJoinerRepo

data class JoinerCtx(
    val campsiteParentJoinerRepo: CampsiteParentJoinerRepo,
    /** YAML `args:` map for the entry; empty when not declared. */
    val args: Map<String, String> = emptyMap(),
)
