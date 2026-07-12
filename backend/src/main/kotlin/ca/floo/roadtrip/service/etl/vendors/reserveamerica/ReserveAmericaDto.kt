package ca.floo.roadtrip.service.etl.vendors.reserveamerica

import java.time.Instant

data class ReserveAmericaDto(
    val parks: List<ParsedPark>,
    val fetchedAt: Instant,
)
