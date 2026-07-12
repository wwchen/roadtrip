package ca.floo.roadtrip.models.metadata.registry

import kotlinx.serialization.Serializable

@Serializable
data class EtlEntry(
    val slug: String,
    val adapter: String,
    val inputs: List<String> = emptyList(),
    val args: Map<String, String> = emptyMap(),
)
