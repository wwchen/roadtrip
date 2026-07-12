package ca.floo.roadtrip.service.etl.vendors.aspira

import kotlinx.serialization.Serializable

/** Materialized intermediate output. Downstream join-by-name ETL consumes this. */
@Serializable
data class AspiraLeavesPayload(
    val slug: String,
    val leaves: List<AspiraLeaf>,
)
