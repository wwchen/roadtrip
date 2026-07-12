package ca.floo.roadtrip.service.etl.vendors.bcparks

import kotlinx.serialization.Serializable

@Serializable
data class BcParksPhoto(
    val imageUrl: String? = null,
    val isFeatured: Boolean? = null,
    val isActive: Boolean? = null,
    val sortOrder: Int? = null,
)
