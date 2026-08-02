package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityWatchCreateRequest
import ca.floo.roadtrip.model.api.AvailabilityWatchListResponse
import ca.floo.roadtrip.model.api.AvailabilityWatchResponse
import ca.floo.roadtrip.model.api.AvailabilityWatchUpdateRequest
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.repo.AvailabilityWatchRepo

internal sealed class AvailabilityWatchControllerResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : AvailabilityWatchControllerResult<T>()

    data class Invalid(
        val error: String,
        val detail: String? = null,
    ) : AvailabilityWatchControllerResult<Nothing>()

    data object NotFound : AvailabilityWatchControllerResult<Nothing>()
}

internal class AvailabilityWatchController(
    private val watchRepo: AvailabilityWatchRepo,
    private val watchService: AvailabilityWatchService,
    private val watchMapper: AvailabilityWatchApiMapper,
) {
    fun list(
        status: WatchStatus?,
        poiId: Long?,
        campsiteId: Long?,
        limit: Int,
        offset: Int,
    ): AvailabilityWatchListResponse {
        val rows = watchRepo.list(status, poiId, campsiteId, ownerUserId = null, limit, offset)
        val total = watchRepo.count(status, poiId, campsiteId)
        return watchMapper.listResponse(rows, total, limit, offset)
    }

    fun get(id: Long): AvailabilityWatchResponse? =
        watchRepo
            .findById(id)
            ?.let { watchMapper.response(it, includeCapabilities = true) }

    fun create(req: AvailabilityWatchCreateRequest): AvailabilityWatchControllerResult<AvailabilityWatchResponse> {
        val parsed =
            when (val mapped = AvailabilityWatchRequestMapper.parseCreate(req)) {
                is WatchRequestMapping.Invalid ->
                    return AvailabilityWatchControllerResult.Invalid(mapped.error, mapped.detail)
                is WatchRequestMapping.Valid -> mapped.value
            }
        val watch =
            try {
                watchService.create(
                    ownerUserId = UserId(0L), // TODO Task 5: owner from authenticated user
                    targets = parsed.targets,
                    campsiteFilters = req.campsiteFilters,
                    startDate = parsed.dateWindow.startDate,
                    endDate = parsed.dateWindow.endDate,
                    cadenceSec = req.cadenceSec,
                    triggerKinds = req.triggerKinds,
                    triggerConfig = req.triggerConfig,
                    stopWhenTriggered = req.stopWhenTriggered,
                )
            } catch (e: AvailabilityWatchValidationException) {
                return AvailabilityWatchControllerResult.Invalid(e.error, e.message)
            }
        return AvailabilityWatchControllerResult.Ok(watchMapper.response(watch))
    }

    fun update(
        id: Long,
        req: AvailabilityWatchUpdateRequest,
    ): AvailabilityWatchControllerResult<AvailabilityWatchResponse> {
        val parsed =
            when (val mapped = AvailabilityWatchRequestMapper.parseUpdate(req)) {
                is WatchRequestMapping.Invalid ->
                    return AvailabilityWatchControllerResult.Invalid(mapped.error, mapped.detail)
                is WatchRequestMapping.Valid -> mapped.value
            }
        val updated =
            try {
                watchService.update(
                    id,
                    targets = parsed.targets,
                    campsiteFilters = req.campsiteFilters,
                    startDate = parsed.dateWindow?.startDate,
                    endDate = parsed.dateWindow?.endDate,
                    cadenceSec = req.cadenceSec,
                    triggerKinds = req.triggerKinds,
                    triggerConfig = req.triggerConfig,
                    stopWhenTriggered = req.stopWhenTriggered,
                    status = parsed.status,
                )
            } catch (e: AvailabilityWatchValidationException) {
                return AvailabilityWatchControllerResult.Invalid(e.error, e.message)
            } ?: return AvailabilityWatchControllerResult.NotFound
        return AvailabilityWatchControllerResult.Ok(watchMapper.response(updated))
    }

    fun delete(id: Long): Boolean = watchService.delete(id)
}
