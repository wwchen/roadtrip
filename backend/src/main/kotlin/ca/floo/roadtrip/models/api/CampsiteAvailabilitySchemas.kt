package ca.floo.roadtrip.models.api

import kotlinx.serialization.Serializable

// /api/campsite/availability/bulk — trip-planner endpoint. ids are pois.id
// (not provider-specific keys); the BE dispatches to the right cache by
// provider_ref. Per-id failures land as a non-200 `status` on that entry's
// record; the rest of the call still succeeds.
@Serializable
data class BulkAvailRequestSchema(
    val ids: List<Long>,
    val start: String,
    val nights: Int,
)

@Serializable
data class BulkAvailEntrySchema(
    val id: Long,
    val status: Int,
    val available_dates: List<String>,
)

@Serializable
data class BulkAvailResponseSchema(
    val start: String,
    val nights: Int,
    val results: List<BulkAvailEntrySchema>,
)

@Serializable
data class AvailabilityErrorSchema(
    val state: String = "error",
    val error: String,
    val retry_after_s: Int? = null,
)

@Serializable
data class AvailabilityEmptySchema(
    val provider: String = "none",
    val state: String = "empty",
    val summary: String = "No availability provider",
)
