package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityWatchCreateRequest
import ca.floo.roadtrip.model.api.AvailabilityWatchListResponse
import ca.floo.roadtrip.model.api.AvailabilityWatchResponse
import ca.floo.roadtrip.model.api.AvailabilityWatchUpdateRequest
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.auth.WatchAccessResolver

private const val DELIVERY_CHANGE_FORBIDDEN = "delivery_change_forbidden"

internal sealed class AvailabilityWatchControllerResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : AvailabilityWatchControllerResult<T>()

    data class Invalid(
        val error: String,
        val detail: String? = null,
    ) : AvailabilityWatchControllerResult<Nothing>()

    data object NotFound : AvailabilityWatchControllerResult<Nothing>()

    /** No credential at all where one is needed — the route answers `401`. */
    data object Unauthenticated : AvailabilityWatchControllerResult<Nothing>()

    /** `403`, not `404`: the caller already proved they may see this watch. */
    data class Forbidden(
        val error: String,
        val detail: String? = null,
    ) : AvailabilityWatchControllerResult<Nothing>()
}

internal class AvailabilityWatchController(
    private val watchRepo: AvailabilityWatchRepo,
    private val watchService: AvailabilityWatchService,
    private val watchMapper: AvailabilityWatchApiMapper,
    private val accessResolver: WatchAccessResolver,
) {
    fun list(
        principal: Principal.User,
        status: WatchStatus?,
        poiId: Long?,
        campsiteId: Long?,
        limit: Int,
        offset: Int,
    ): AvailabilityWatchListResponse {
        val user = accessResolver.account(principal)
        val ownerFilter = if (user.isAdmin) null else user.id.value
        val rows = watchRepo.list(status, poiId, campsiteId, ownerFilter, limit, offset)
        val total = watchRepo.count(status, poiId, campsiteId, ownerFilter)
        return watchMapper.listResponse(rows, total, limit, offset)
    }

    fun get(
        principal: Principal,
        id: Long,
        magicLinkToken: String?,
    ): AvailabilityWatchControllerResult<AvailabilityWatchResponse> =
        withAccess(principal, id, magicLinkToken) { granted ->
            AvailabilityWatchControllerResult.Ok(
                watchMapper.response(
                    granted.watch,
                    includeCapabilities = true,
                    redactDelivery = granted.viaMagicLink,
                ),
            )
        }

    fun create(
        principal: Principal.User,
        req: AvailabilityWatchCreateRequest,
    ): AvailabilityWatchControllerResult<AvailabilityWatchResponse> {
        val user = accessResolver.account(principal)
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
        principal: Principal,
        id: Long,
        req: AvailabilityWatchUpdateRequest,
        magicLinkToken: String?,
    ): AvailabilityWatchControllerResult<AvailabilityWatchResponse> =
        withAccess(principal, id, magicLinkToken) { granted ->
            if (req.redirectsDelivery() && granted.viaMagicLink) {
                return@withAccess AvailabilityWatchControllerResult.Forbidden(
                    DELIVERY_CHANGE_FORBIDDEN,
                    "a manage link can retime or stop this watch, but not change where its alerts are sent",
                )
            }
            applyUpdate(id, req, redactDelivery = granted.viaMagicLink)
        }

    /** The update itself, once the caller has been cleared to make it. */
    private fun applyUpdate(
        id: Long,
        req: AvailabilityWatchUpdateRequest,
        redactDelivery: Boolean,
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
        return AvailabilityWatchControllerResult.Ok(watchMapper.response(updated, redactDelivery = redactDelivery))
    }

    fun delete(
        principal: Principal,
        id: Long,
        magicLinkToken: String?,
    ): AvailabilityWatchControllerResult<Unit> =
        withAccess(principal, id, magicLinkToken) {
            if (watchService.delete(id)) {
                AvailabilityWatchControllerResult.Ok(Unit)
            } else {
                AvailabilityWatchControllerResult.NotFound
            }
        }

    /** Runs [body] only when the caller may act on this watch. */
    private fun <T> withAccess(
        principal: Principal,
        id: Long,
        magicLinkToken: String?,
        body: (WatchAccessResolver.Resolution.Granted) -> AvailabilityWatchControllerResult<T>,
    ): AvailabilityWatchControllerResult<T> =
        when (val resolution = accessResolver.resolve(principal, id, magicLinkToken)) {
            is WatchAccessResolver.Resolution.Granted -> body(resolution)
            WatchAccessResolver.Resolution.Unauthenticated -> AvailabilityWatchControllerResult.Unauthenticated
            WatchAccessResolver.Resolution.NotFound -> AvailabilityWatchControllerResult.NotFound
        }
}

/**
 * Whether this update would change where the alerts go. Both fields count:
 * `trigger_kinds` adds a transport, `trigger_config` carries the channel
 * override.
 */
private fun AvailabilityWatchUpdateRequest.redirectsDelivery(): Boolean = triggerKinds != null || triggerConfig != null
