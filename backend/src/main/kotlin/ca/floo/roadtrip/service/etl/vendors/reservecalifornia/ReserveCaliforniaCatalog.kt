package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

import java.time.Instant

data class ReserveCaliforniaCatalog(
    val places: Map<Long, ReserveCaliforniaPlace>,
    val facilities: Map<Long, ReserveCaliforniaFacility>,
    val grids: Map<Long, ReserveCaliforniaGridCatalog>,
    val fetchedAt: Instant,
)
