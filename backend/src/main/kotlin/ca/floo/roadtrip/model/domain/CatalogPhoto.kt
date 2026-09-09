package ca.floo.roadtrip.model.domain

import kotlinx.serialization.Serializable

/** One entry of a `photos` JSONB array on `campgrounds` or `campsites`. */
@Serializable
data class CatalogPhoto(
    val url: String,
)
