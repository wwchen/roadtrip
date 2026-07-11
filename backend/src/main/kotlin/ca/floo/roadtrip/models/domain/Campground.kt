package ca.floo.roadtrip.models.domain

import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * One row in the `campgrounds` table.
 */
data class Campground(
    val id: Long,
    val name: String,
    val status: String?,
    val statusDescription: String?,
    val kind: String?,
    val shortDescription: String?,
    val mediumDescription: String?,
    val longDescription: String?,
    val location: JsonElement,
    val defaultCampsiteSchedule: JsonElement,
    val amenities: JsonElement,
    val maxRvLength: Double?,
    val maxTrailerLength: Double?,
    val hasPullThroughSites: Boolean?,
    val bigRigFriendly: Boolean?,
    val reservationUrl: String?,
    val links: JsonElement,
    val photos: JsonElement,
    val alerts: JsonElement,
    val price: JsonElement,
    val cellService: JsonElement,
    val management: JsonElement,
    val contact: JsonElement,
    val connections: JsonElement,
    val metadata: JsonElement,
    val sourcePayload: JsonElement,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val dataSource: String,
    val matchGroupId: Long?,
    val preferredAvailabilitySource: String?,
    val primaryVendorRefId: Long,
)
