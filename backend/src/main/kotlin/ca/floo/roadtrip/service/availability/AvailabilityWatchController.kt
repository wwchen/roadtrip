package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityWatchCreateRequest
import ca.floo.roadtrip.model.api.AvailabilityWatchListResponse
import ca.floo.roadtrip.model.api.AvailabilityWatchResponse
import ca.floo.roadtrip.model.api.AvailabilityWatchUpdateRequest
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.User
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.UserRepo

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
    private val userRepo: UserRepo,
) {
    // Resolves the account for the calling principal. The route guard guarantees a
    // Principal.User reached us; a missing app_user row would be a data bug, so
    // failing loudly is correct.
    private fun resolve(principal: Principal.User): User =
        requireNotNull(userRepo.findById(principal.userId)) {
            "no app_user for authenticated principal ${principal.userId}"
        }

    fun list(
        principal: Principal.User,
        status: WatchStatus?,
        poiId: Long?,
        campsiteId: Long?,
        limit: Int,
        offset: Int,
    ): AvailabilityWatchListResponse {
        val user = resolve(principal)
        val ownerFilter = if (user.isAdmin) null else user.id.value
        val rows = watchRepo.list(status, poiId, campsiteId, ownerFilter, limit, offset)
        val total = watchRepo.count(status, poiId, campsiteId, ownerFilter)
        return watchMapper.listResponse(rows, total, limit, offset)
    }

    fun get(
        principal: Principal.User,
        id: Long,
    ): AvailabilityWatchResponse? {
        val user = resolve(principal)
        val watch = watchRepo.findById(id) ?: return null
        if (!user.isAdmin && watch.ownerUserId != user.id.value) return null // 404, don't leak existence
        return watchMapper.response(watch, includeCapabilities = true)
    }

    fun create(
        principal: Principal.User,
        req: AvailabilityWatchCreateRequest,
    ): AvailabilityWatchControllerResult<AvailabilityWatchResponse> {
        val user = resolve(principal)
        val parsed =
            when (val mapped = AvailabilityWatchRequestMapper.parseCreate(req)) {
                is WatchRequestMapping.Invalid ->
                    return AvailabilityWatchControllerResult.Invalid(mapped.error, mapped.detail)
                is WatchRequestMapping.Valid -> mapped.value
            }
        val watch =
            try {
                watchService.create(
                    ownerUserId = user.id,
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
        principal: Principal.User,
        id: Long,
        req: AvailabilityWatchUpdateRequest,
    ): AvailabilityWatchControllerResult<AvailabilityWatchResponse> {
        val user = resolve(principal)
        val existing = watchRepo.findById(id) ?: return AvailabilityWatchControllerResult.NotFound
        if (!user.isAdmin && existing.ownerUserId != user.id.value) return AvailabilityWatchControllerResult.NotFound
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

    fun delete(
        principal: Principal.User,
        id: Long,
    ): Boolean {
        val user = resolve(principal)
        val existing = watchRepo.findById(id) ?: return false
        if (!user.isAdmin && existing.ownerUserId != user.id.value) return false
        return watchService.delete(id)
    }

    // The token-scoped variants below back the magic-link email flow. A
    // resolved management token already proves scope over exactly this watch
    // id (see WatchManagementTokenService), so there is no separate owner
    // check to perform here — the token *is* the authorization.

    fun getByToken(id: Long): AvailabilityWatchResponse? {
        val watch = watchRepo.findById(id) ?: return null
        return watchMapper.response(watch, includeCapabilities = true)
    }

    fun updateByToken(
        id: Long,
        req: AvailabilityWatchUpdateRequest,
    ): AvailabilityWatchControllerResult<AvailabilityWatchResponse> {
        watchRepo.findById(id) ?: return AvailabilityWatchControllerResult.NotFound
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

    fun deleteByToken(id: Long): Boolean {
        watchRepo.findById(id) ?: return false
        return watchService.delete(id)
    }
}
