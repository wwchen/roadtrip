package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

import kotlinx.serialization.json.JsonElement

data class ReserveCaliforniaFacility(
    val facilityId: Long,
    val placeId: Long?,
    val name: String?,
    val facilityTypeNew: Long?,
    val facilityBehaviourType: Long?,
    val allowWebBooking: Boolean?,
    val raw: JsonElement,
) {
    val isStandardBookable: Boolean
        get() = facilityTypeNew != 2L && facilityBehaviourType != 2L && allowWebBooking != false
}
