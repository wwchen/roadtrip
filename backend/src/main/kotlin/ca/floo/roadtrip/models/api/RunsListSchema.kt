package ca.floo.roadtrip.models.api

import kotlinx.serialization.Serializable

@Serializable
data class RunsListSchema(
    val runs: List<IngestRunListItemSchema>,
)
