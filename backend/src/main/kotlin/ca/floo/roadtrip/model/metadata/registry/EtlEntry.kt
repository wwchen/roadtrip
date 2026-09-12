package ca.floo.roadtrip.model.metadata.registry

import kotlinx.serialization.Serializable

@Serializable
data class EtlEntry(
    val slug: String,
    val adapter: String,
    val inputs: List<String> = emptyList(),
    val args: Map<String, String> = emptyMap(),
    /** Required for the adapters that join geometry by name; rejected for the rest. */
    val geometry: GeometryPolicy? = null,
)
