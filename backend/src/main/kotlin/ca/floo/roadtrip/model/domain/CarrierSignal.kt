package ca.floo.roadtrip.model.domain

import kotlinx.serialization.Serializable

/** One entry of the `campgrounds.cell_service` JSONB array. */
@Serializable
data class CarrierSignal(
    val carrier: Carrier,
    val average: Double,
    val count: Int? = null,
)
