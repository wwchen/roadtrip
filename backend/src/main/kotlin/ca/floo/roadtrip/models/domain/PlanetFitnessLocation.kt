package ca.floo.roadtrip.models.domain

import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * One row in the `planet_fitness_locations` table.
 */
data class PlanetFitnessLocation(
    val id: Long,
    val locationId: String,
    val name: String,
    val address: JsonElement,
    val region: String?,
    val country: String?,
    val phone: String?,
    val infoUrl: String?,
    val amenities: JsonElement,
    val payload: JsonElement,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)
