package ca.floo.roadtrip.service.etl.vendors.tesla

import kotlinx.serialization.Serializable

@Serializable
data class TeslaDetailInner(
    val data: TeslaLocationDetail = TeslaLocationDetail(),
)
