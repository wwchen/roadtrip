package ca.floo.roadtrip.service.etl.vendors.bcparks

import kotlinx.serialization.json.JsonObject
import java.time.Instant

data class BcParksDto(
    val rows: List<BcParksRow>,
    val rawById: Map<Long, JsonObject>,
    val fetchedAt: Instant,
)
