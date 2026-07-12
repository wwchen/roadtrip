package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

import kotlinx.serialization.json.JsonObject

data class ReserveCaliforniaUnit(
    val unitId: Long,
    val name: String?,
    val raw: JsonObject,
)
