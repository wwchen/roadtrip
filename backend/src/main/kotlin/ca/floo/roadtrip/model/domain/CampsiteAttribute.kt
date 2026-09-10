package ca.floo.roadtrip.model.domain

import kotlinx.serialization.Serializable

/** One entry of the `campsites.attributes` JSONB array. */
@Serializable
data class CampsiteAttribute(
    val name: String,
    val value: String? = null,
)
