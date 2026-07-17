package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class RunsListSchema(
    val runs: List<IngestRunListItemSchema>,
)
