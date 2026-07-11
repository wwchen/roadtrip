package ca.floo.roadtrip.models.domain

import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * One row in the `campsites` table.
 */
data class Campsite(
    val id: Long,
    val campgroundId: Long,
    val name: String,
    val kind: String,
    val loopName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val reservationUrl: String?,
    val equipment: JsonElement?,
    val kindListed: String?,
    val schedule: JsonElement,
    val price: JsonElement,
    val firepit: Boolean?,
    val picnicTable: Boolean?,
    val adaAccessible: Boolean?,
    val waterHookups: Boolean?,
    val electricHookups: Boolean?,
    val sewerHookups: Boolean?,
    val maxPeople: Int?,
    val maxCars: Int?,
    val pullThrough: Boolean?,
    val drivewayLength: Int?,
    val maxRvLength: Int?,
    val maxTrailerLength: Double?,
    val photos: JsonElement,
    val sourcePayload: JsonElement,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val dataSource: String,
    val matchGroupId: Long?,
    val primaryVendorRefId: Long,
)
