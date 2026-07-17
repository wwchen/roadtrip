package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.api.AvailabilityWatchCreateRequest
import ca.floo.roadtrip.models.api.AvailabilityWatchTargetSchema
import ca.floo.roadtrip.models.api.AvailabilityWatchUpdateRequest
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo

private const val MIN_CADENCE_SEC = 5

internal sealed class WatchRequestMapping<out T> {
    data class Valid<T>(
        val value: T,
    ) : WatchRequestMapping<T>()

    data class Invalid(
        val error: String,
        val detail: String? = null,
    ) : WatchRequestMapping<Nothing>()
}

internal data class ParsedCreateWatchRequest(
    val targets: List<AvailabilityWatchTargetRepo.TargetInput>,
    val dateWindow: AvailabilityWatchDateWindow,
)

internal data class ParsedUpdateWatchRequest(
    val targets: List<AvailabilityWatchTargetRepo.TargetInput>?,
    val dateWindow: AvailabilityWatchDateWindow?,
    val status: WatchStatus?,
)

internal object AvailabilityWatchRequestMapper {
    fun parseCreate(req: AvailabilityWatchCreateRequest): WatchRequestMapping<ParsedCreateWatchRequest> {
        validateCadence(req.cadenceSec)?.let { return it }
        validateTriggerKinds(req.triggerKinds)?.let { return it }
        val targets =
            when (val mapped = createTargets(req)) {
                is WatchRequestMapping.Invalid -> return mapped
                is WatchRequestMapping.Valid -> mapped.value
            }
        val window =
            AvailabilityWatchDateWindow.parse(req.startDate, req.endDate)
                ?: return invalidDateWindow("end_date must be after start_date")
        return valid(ParsedCreateWatchRequest(targets = targets, dateWindow = window))
    }

    fun parseUpdate(req: AvailabilityWatchUpdateRequest): WatchRequestMapping<ParsedUpdateWatchRequest> {
        validateCadence(req.cadenceSec)?.let { return it }
        req.triggerKinds?.let { validateTriggerKinds(it)?.let { error -> return error } }
        val status =
            req.status?.let {
                WatchStatus.parse(it)
                    ?: return WatchRequestMapping.Invalid(
                        error = "invalid_status",
                        detail = "status must be active, paused, or done",
                    )
            }
        val window =
            when {
                (req.startDate == null) xor (req.endDate == null) ->
                    return invalidDateWindow("start_date and end_date must be updated together")
                req.startDate != null && req.endDate != null ->
                    AvailabilityWatchDateWindow.parse(req.startDate, req.endDate)
                        ?: return invalidDateWindow("end_date must be after start_date")
                else -> null
            }
        val targets =
            when (val mapped = updateTargets(req)) {
                is WatchRequestMapping.Invalid -> return mapped
                is WatchRequestMapping.Valid -> mapped.value
            }
        return valid(ParsedUpdateWatchRequest(targets = targets, dateWindow = window, status = status))
    }

    private fun createTargets(req: AvailabilityWatchCreateRequest): WatchRequestMapping<List<AvailabilityWatchTargetRepo.TargetInput>> {
        val legacyScopeCount = listOf(req.poiId, req.campsiteId).count { it != null }
        val targets = req.targets
        if (targets != null && legacyScopeCount > 0) {
            return invalidScope("specify either targets or poi_id/campsite_id, not both")
        }
        if (targets != null) return mapTargets(targets)
        if (legacyScopeCount != 1) {
            return invalidScope("exactly one of targets, poi_id, or campsite_id must be set")
        }
        return valid(
            listOf(
                AvailabilityWatchTargetRepo.TargetInput(poiId = req.poiId, campsiteId = req.campsiteId),
            ),
        )
    }

    private fun updateTargets(req: AvailabilityWatchUpdateRequest): WatchRequestMapping<List<AvailabilityWatchTargetRepo.TargetInput>?> =
        req.targets?.let { targets ->
            when (val mapped = mapTargets(targets)) {
                is WatchRequestMapping.Invalid -> mapped
                is WatchRequestMapping.Valid -> valid(mapped.value)
            }
        } ?: valid(null)

    private fun mapTargets(
        targets: List<AvailabilityWatchTargetSchema>,
    ): WatchRequestMapping<List<AvailabilityWatchTargetRepo.TargetInput>> {
        if (targets.isEmpty()) return invalidScope("targets must be non-empty")
        return valid(
            targets.map { target ->
                if ((target.poiId == null) == (target.campsiteId == null)) {
                    return invalidScope("each target must set exactly one of poi_id/campsite_id")
                }
                AvailabilityWatchTargetRepo.TargetInput(poiId = target.poiId, campsiteId = target.campsiteId)
            },
        )
    }

    private fun validateCadence(cadenceSec: Int?): WatchRequestMapping.Invalid? {
        if (cadenceSec != null && cadenceSec < MIN_CADENCE_SEC) {
            return WatchRequestMapping.Invalid("invalid_cadence", "cadence_sec must be >= $MIN_CADENCE_SEC")
        }
        return null
    }

    private fun validateTriggerKinds(triggerKinds: List<String>): WatchRequestMapping.Invalid? {
        if (triggerKinds.isEmpty()) {
            return WatchRequestMapping.Invalid("invalid_triggers", "trigger_kinds must be non-empty")
        }
        return null
    }

    private fun <T> valid(value: T): WatchRequestMapping.Valid<T> = WatchRequestMapping.Valid(value)

    private fun invalidScope(detail: String): WatchRequestMapping.Invalid =
        WatchRequestMapping.Invalid(error = "invalid_scope", detail = detail)

    private fun invalidDateWindow(detail: String): WatchRequestMapping.Invalid =
        WatchRequestMapping.Invalid(error = "invalid_date_window", detail = detail)
}
