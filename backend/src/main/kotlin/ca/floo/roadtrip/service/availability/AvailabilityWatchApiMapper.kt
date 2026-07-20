package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityWatchListResponse
import ca.floo.roadtrip.model.api.AvailabilityWatchResponse
import ca.floo.roadtrip.model.api.AvailabilityWatchSchema
import ca.floo.roadtrip.model.api.AvailabilityWatchTargetSchema
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampsiteRepo

internal class AvailabilityWatchApiMapper(
    private val campsiteRepo: CampsiteRepo,
    private val scopeResolver: WatchScopeResolver,
    private val watchCapabilityService: WatchCapabilityService? = null,
) {
    fun listResponse(
        rows: List<AvailabilityWatchRepo.Watch>,
        total: Int,
        limit: Int,
        offset: Int,
    ): AvailabilityWatchListResponse =
        AvailabilityWatchListResponse(
            total = total,
            limit = limit,
            offset = offset,
            watches = rows.map(::schema),
        )

    fun response(
        watch: AvailabilityWatchRepo.Watch,
        includeCapabilities: Boolean = false,
    ): AvailabilityWatchResponse =
        AvailabilityWatchResponse(
            watch = schema(watch),
            watchCapabilities =
                if (includeCapabilities) {
                    watchCapabilityService?.capabilitiesFor(scopeResolver.resolve(watch))
                } else {
                    null
                },
        )

    fun schema(watch: AvailabilityWatchRepo.Watch): AvailabilityWatchSchema {
        val firstTarget = watch.targets.firstOrNull()
        val singleCampsite =
            firstTarget
                ?.campsiteId
                ?.takeIf { watch.targets.size == 1 }
                ?.let(campsiteRepo::findById)
        return AvailabilityWatchSchema(
            id = watch.id,
            targets =
                watch.targets.map { target ->
                    AvailabilityWatchTargetSchema(poiId = target.poiId, campsiteId = target.campsiteId)
                },
            poiId = firstTarget?.poiId,
            campsiteId = firstTarget?.campsiteId,
            campsite = singleCampsite,
            campsiteFilters = watch.campsiteFilters,
            startDate = watch.startDate.toString(),
            endDate = watch.endDate.toString(),
            cadenceSec = watch.cadenceSec,
            triggerKinds = watch.triggerKinds,
            triggerConfig = watch.triggerConfig,
            stopWhenTriggered = watch.stopWhenTriggered,
            status = watch.status.wireValue,
            createdAt = watch.createdAt.toString(),
            updatedAt = watch.updatedAt.toString(),
            lastRunAt = watch.lastRun?.completedAt?.toString(),
            lastRunStatus = watch.lastRun?.status,
            lastRunError = watch.lastRun?.error,
        )
    }
}
