package ca.floo.roadtrip.model.domain

import kotlinx.serialization.json.JsonElement

/**
 * Campsite projection used at the availability-provider boundary.
 *
 * This is not the `campsites` table row. It includes the selected provider
 * reference and vendor id needed to call upstream booking systems.
 */
data class CampsiteAvailabilityTarget(
    val id: Long,
    val vendor: String,
    val vendorId: String,
    val name: String?,
    val loop: String?,
    val siteType: String?,
    val raw: JsonElement?,
    val tags: JsonElement? = null,
    val providerRef: JsonElement? = null,
)
