package ca.floo.roadtrip.service.etl.vendors.tesla

import kotlinx.serialization.Serializable

// Tesla per-slug detail envelope shape: payload.data.data.{name, address, …}.
@Serializable
data class TeslaDetailEnvelope(
    val data: TeslaDetailInner = TeslaDetailInner(),
)
