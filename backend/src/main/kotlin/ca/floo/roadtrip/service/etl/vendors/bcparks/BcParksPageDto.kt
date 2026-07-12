package ca.floo.roadtrip.service.etl.vendors.bcparks

import kotlinx.serialization.Serializable

@Serializable
data class BcParksPageDto(
    val data: List<BcParksRow> = emptyList(),
)
