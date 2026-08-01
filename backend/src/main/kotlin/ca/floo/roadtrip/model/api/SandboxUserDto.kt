package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class SandboxUserDto(
    val id: Long,
    val name: String,
    val roles: List<String>,
)
