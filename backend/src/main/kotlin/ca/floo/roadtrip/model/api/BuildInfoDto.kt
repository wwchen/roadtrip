package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

/** Identifies the running build. Surfaced by the sandbox banner; safe to expose. */
@Serializable
data class BuildInfoDto(
    val env: String,
    val sha: String,
    val branch: String,
)
