package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

data class ReserveCaliforniaGridCatalog(
    val facilityId: Long,
    val placeId: Long?,
    val facilityName: String?,
    val units: List<ReserveCaliforniaUnit>,
)
