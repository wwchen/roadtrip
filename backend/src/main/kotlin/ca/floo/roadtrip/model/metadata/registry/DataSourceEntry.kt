package ca.floo.roadtrip.model.metadata.registry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DataSourceEntry(
    val slug: String,
    val name: String,
    val fetcher: Fetcher,
    @kotlinx.serialization.SerialName("depends_on")
    val dependsOn: List<String> = emptyList(),
)
