package ca.floo.roadtrip.service.etl.vendors.tesla

import kotlinx.serialization.json.JsonObject
import java.time.Instant

data class TeslaIndexDto(
    val rows: List<TeslaIndexRow>,
    val rawBySlug: Map<String, JsonObject>,
    val fetchedAt: Instant,
)
