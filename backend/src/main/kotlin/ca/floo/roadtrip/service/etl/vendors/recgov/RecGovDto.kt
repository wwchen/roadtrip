package ca.floo.roadtrip.service.etl.vendors.recgov

import kotlinx.serialization.json.JsonObject
import java.time.Instant

data class RecGovDto(
    val rows: List<Facility>,
    val rawById: Map<Long, JsonObject>,
    val enrichmentById: Map<Long, JsonObject>,
    val fetchedAt: Instant,
)
