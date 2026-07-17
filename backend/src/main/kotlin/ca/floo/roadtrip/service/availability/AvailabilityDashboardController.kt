package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityChangeSchema
import ca.floo.roadtrip.model.api.AvailabilityChangesListResponse
import ca.floo.roadtrip.model.api.AvailabilityPollerSchema
import ca.floo.roadtrip.model.api.AvailabilityPollersListResponse
import ca.floo.roadtrip.model.api.AvailabilityPollersSummary
import ca.floo.roadtrip.model.api.AvailabilityRunSchema
import ca.floo.roadtrip.model.api.AvailabilityRunsListResponse
import ca.floo.roadtrip.model.api.AvailabilitySnapshotStatsSchema
import ca.floo.roadtrip.model.api.AvailabilitySnapshotsSummaryResponse
import ca.floo.roadtrip.model.api.CheckNowCooldownDto
import ca.floo.roadtrip.model.api.CheckNowResponseDto
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

internal sealed class AvailabilityDashboardResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : AvailabilityDashboardResult<T>()

    data class Invalid(
        val error: String,
        val detail: String? = null,
    ) : AvailabilityDashboardResult<Nothing>()

    data class NotFound(
        val error: String,
        val detail: String? = null,
    ) : AvailabilityDashboardResult<Nothing>()
}

internal sealed class ForcePollerResult {
    data class Accepted(
        val value: CheckNowResponseDto,
    ) : ForcePollerResult()

    data class Cooldown(
        val value: CheckNowCooldownDto,
    ) : ForcePollerResult()

    data class NotFound(
        val error: String,
        val detail: String? = null,
    ) : ForcePollerResult()
}

internal class AvailabilityDashboardController(
    private val pollers: AvailabilityPollerRepo,
    private val runs: AvailabilityRunRepo,
    private val availability: AvailabilityRepo,
    private val campsites: CampsiteRepo,
    private val forcePullCooldown: Duration,
    private val now: () -> OffsetDateTime = { OffsetDateTime.now() },
) {
    fun listPollers(
        active: Boolean?,
        limit: Int,
        offset: Int,
    ): AvailabilityPollersListResponse {
        val rows = pollers.list(active = active, limit = limit, offset = offset)
        val total = pollers.count(active = active)
        return AvailabilityPollersListResponse(
            total = total,
            limit = limit,
            offset = offset,
            pollers = rows.map { it.toSchema() },
        )
    }

    fun pollersSummary(): AvailabilityPollersSummary {
        val summary = pollers.summary(now())
        return AvailabilityPollersSummary(
            active = summary.active,
            dormant = summary.dormant,
            dueNow = summary.dueNow,
            claimed = summary.claimed,
        )
    }

    fun listRunsForPoller(
        pollerId: Long,
        limit: Int,
    ): AvailabilityRunsListResponse =
        AvailabilityRunsListResponse(
            runs = runs.listForPoller(pollerId, limit = limit).map { it.toSchema() },
        )

    fun forcePoller(pollerId: Long): ForcePollerResult =
        when (val result = pollers.forcePull(pollerId, now(), cooldown = forcePullCooldown)) {
            is AvailabilityPollerRepo.ForcePullResult.Accepted ->
                ForcePollerResult.Accepted(
                    CheckNowResponseDto(pollerId = pollerId, nextRunAt = result.nextRunAt.toString()),
                )
            is AvailabilityPollerRepo.ForcePullResult.Cooldown ->
                ForcePollerResult.Cooldown(
                    CheckNowCooldownDto(pollerId = pollerId, retryAfterSec = result.retryAfterSec),
                )
            AvailabilityPollerRepo.ForcePullResult.NotFound ->
                ForcePollerResult.NotFound("poller_not_found", "no poller with id $pollerId")
        }

    fun listRuns(
        status: String?,
        pollerId: Long?,
        since: OffsetDateTime?,
        limit: Int,
    ): AvailabilityRunsListResponse =
        AvailabilityRunsListResponse(
            runs = runs.listSince(since = since, status = status, pollerId = pollerId, limit = limit).map { it.toSchema() },
        )

    fun listChanges(
        campsiteId: Long?,
        poiId: Long?,
        targetDate: LocalDate?,
        limit: Int,
    ): AvailabilityDashboardResult<AvailabilityChangesListResponse> {
        if ((campsiteId == null) == (poiId == null)) {
            return AvailabilityDashboardResult.Invalid(
                "invalid_filter",
                "exactly one of campsite_id or poi_id must be set",
            )
        }
        val rows =
            if (campsiteId != null) {
                campsites.findById(campsiteId)
                    ?: return AvailabilityDashboardResult.NotFound(
                        "campsite_not_found",
                        "no campsite with id $campsiteId",
                    )
                availability.listForCampsite(campsiteId, targetDate = targetDate, limit = limit)
            } else {
                val poiCampsites = campsites.findByPoi(poiId!!)
                if (poiCampsites.isEmpty()) {
                    return AvailabilityDashboardResult.NotFound(
                        "poi_not_found",
                        "no campsites for poi $poiId",
                    )
                }
                availability.listForCampsites(
                    campsiteIds = poiCampsites.map { it.id },
                    targetDate = targetDate,
                    limit = limit,
                )
            }
        return AvailabilityDashboardResult.Ok(
            AvailabilityChangesListResponse(changes = rows.map { it.toSchema() }),
        )
    }

    fun snapshotsSummary(
        campsiteId: Long?,
        windowHours: Int,
        explicitDates: List<LocalDate>,
    ): AvailabilityDashboardResult<AvailabilitySnapshotsSummaryResponse> {
        val id =
            campsiteId
                ?: return AvailabilityDashboardResult.Invalid(
                    "missing_campsite_id",
                    "campsite_id is required",
                )
        campsites.findById(id)
            ?: return AvailabilityDashboardResult.NotFound(
                "campsite_not_found",
                "no campsite with id $id",
            )
        val currentTime = now()
        val dates =
            explicitDates.ifEmpty {
                availability.datesWithSnapshotsInWindow(
                    campsiteId = id,
                    windowStart = currentTime.minusHours(windowHours.toLong()),
                )
            }
        val stats = availability.summarize(id, dates, now = currentTime, windowHours = windowHours)
        return AvailabilityDashboardResult.Ok(
            AvailabilitySnapshotsSummaryResponse(
                campsiteId = id,
                stats = stats.map { it.toSchema() },
            ),
        )
    }
}

private fun AvailabilityPollerRepo.PollerListItem.toSchema(): AvailabilityPollerSchema =
    AvailabilityPollerSchema(
        id = poller.id,
        provider = poller.provider,
        parentRef = poller.parentRef,
        poiId = poller.poiId,
        active = poller.active,
        nextRunAt = poller.nextRunAt.toString(),
        claimedUntil = poller.claimedUntil?.toString(),
        lastRunAt = poller.lastRunAt?.toString(),
        attachedWatches = attachedWatches,
        createdAt = poller.createdAt.toString(),
        updatedAt = poller.updatedAt.toString(),
    )

private fun AvailabilityRunRepo.Run.toSchema(): AvailabilityRunSchema =
    AvailabilityRunSchema(
        id = id,
        pollerId = pollerId,
        status = status,
        snapshotCount = snapshotCount,
        durationMs = durationMs,
        error = error,
        startedAt = startedAt.toString(),
        completedAt = completedAt?.toString(),
    )

private fun AvailabilityRepo.StatusRun.toSchema(): AvailabilityChangeSchema =
    AvailabilityChangeSchema(
        campsiteId = campsiteId,
        targetDate = targetDate.toString(),
        observedFrom = observedFrom?.toString(),
        observedAt = lastObservedAt.toString(),
        status = status,
        available = available,
    )

private fun AvailabilityRepo.TargetDateStats.toSchema(): AvailabilitySnapshotStatsSchema =
    AvailabilitySnapshotStatsSchema(
        targetDate = targetDate.toString(),
        totalRuns = totalRuns,
        lastOpenAt = lastOpenAt?.toString(),
        isCurrentlyOpen = isCurrentlyOpen,
        currentOrLastOpenWindowSec = currentOrLastOpenWindowSec,
        medianOpenWindowSec = medianOpenWindowSec,
        opensLast24h = opensLast24h,
    )
