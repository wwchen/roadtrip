package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityWatchCreateRequest
import ca.floo.roadtrip.model.api.AvailabilityWatchListResponse
import ca.floo.roadtrip.model.api.AvailabilityWatchResponse
import ca.floo.roadtrip.model.api.AvailabilityWatchUpdateRequest
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.User
import ca.floo.roadtrip.model.domain.auth.WatchCredential
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

    /**
     * Whether [credential] may act on watch [id].
     *
     * The two ways in are deliberately asymmetric. A session is an identity, so
     * it is checked against ownership (with admin as the usual override). A
     * magic link is not an identity at all — it is a secret that names one
     * watch, so the only question is whether it names *this* one. Everything
     * else about the caller is unknown and must stay irrelevant.
     */
    private fun authorizes(
        credential: WatchCredential,
        id: Long,
        watch: AvailabilityWatchRepo.Watch,
    ): Boolean =
        when (credential) {
            is WatchCredential.MagicLink -> credential.watchId == id
            is WatchCredential.Session -> {
                val user = resolve(credential.principal)
                user.isAdmin || watch.ownerUserId == user.id.value
            }
        }

    /** The watch [credential] may act on, or null — indistinguishably absent or forbidden. */
    private fun authorized(
        credential: WatchCredential,
        id: Long,
    ): AvailabilityWatchRepo.Watch? {
        val watch = watchRepo.findById(id) ?: return null
        // 404 rather than 403 for a watch that exists but isn't yours: a
        // distinguishable "forbidden" turns the id space into an oracle for
        // which watches exist.
        return watch.takeIf { authorizes(credential, id, it) }
    }

    fun get(
        credential: WatchCredential,
        id: Long,
    ): AvailabilityWatchResponse? {
        val watch = authorized(credential, id) ?: return null
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
        credential: WatchCredential,
        id: Long,
        req: AvailabilityWatchUpdateRequest,
    ): AvailabilityWatchControllerResult<AvailabilityWatchResponse> {
        authorized(credential, id) ?: return AvailabilityWatchControllerResult.NotFound
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
        credential: WatchCredential,
        id: Long,
    ): Boolean {
        authorized(credential, id) ?: return false
        return watchService.delete(id)
    }
}
