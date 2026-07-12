package ca.floo.roadtrip.service.etl.vendors.tesla

import kotlinx.serialization.Serializable

@Serializable
data class TeslaIndexInner(
    val data: List<TeslaIndexRow> = emptyList(),
)
