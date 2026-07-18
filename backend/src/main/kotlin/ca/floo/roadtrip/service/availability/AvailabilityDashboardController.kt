package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityChangeSchema
import ca.floo.roadtrip.model.api.AvailabilityPollerSchema
import ca.floo.roadtrip.model.api.AvailabilityPollersListResponse
import ca.floo.roadtrip.model.api.AvailabilityPollersSummary
import ca.floo.roadtrip.model.api.AvailabilityRunSchema
import ca.floo.roadtrip.model.api.AvailabilityRunsListResponse
import ca.floo.roadtrip.model.api.AvailabilitySnapshotStatsSchema
import ca.floo.roadtrip.model.api.AvailabilitySnapshotsSummaryResponse
import ca.floo.roadtrip.model.api.CheckNowCooldownDto
import ca.floo.roadtrip.model.api.CheckNowResponseDto
import ca.floo.roadtrip.model.api.ListAvailabilityChangesResponse
import ca.floo.roadtrip.model.domain.Campsite
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
    ): AvailabilityDashboardResult<ListAvailabilityChangesResponse> {
        if ((campsiteId == null) == (poiId == null)) {
            return AvailabilityDashboardResult.Invalid(
                "invalid_filter",
                "exactly one of campsite_id or poi_id must be set",
            )
        }
        val nameMap: Map<Long, String>
        val rows =
            if (campsiteId != null) {
                val cs =
                    campsites.findById(campsiteId)
                        ?: return AvailabilityDashboardResult.NotFound(
                            "campsite_not_found",
                            "no campsite with id $campsiteId",
                        )
                nameMap = mapOf(cs.id to campsiteDisplayName(cs))
                availability.listForCampsite(campsiteId, targetDate = targetDate, limit = limit)
            } else {
                val poiCampsites = campsites.findByPoi(poiId!!)
                if (poiCampsites.isEmpty()) {
                    return AvailabilityDashboardResult.NotFound(
                        "poi_not_found",
                        "no campsites for poi $poiId",
                    )
                }
                nameMap = poiCampsites.associate { it.id to campsiteDisplayName(it) }
                availability.listForCampsites(
                    campsiteIds = poiCampsites.map { it.id },
                    targetDate = targetDate,
                    limit = limit,
                )
            }
        return AvailabilityDashboardResult.Ok(
            ListAvailabilityChangesResponse(
                changes = rows.filter { it.fromStatus != null }.map { it.toSchema(nameMap[it.campsiteId]) },
            ),
        )
    }

    fun changeSummary(
        poiId: Long?,
        explicitDates: List<LocalDate>,
    ): AvailabilityDashboardResult<AvailabilitySnapshotsSummaryResponse> {
        val id =
            poiId
                ?: return AvailabilityDashboardResult.Invalid(
                    "missing_poi_id",
                    "poi_id is required",
                )
        val poiCampsites = campsites.findByPoi(id)
        if (poiCampsites.isEmpty()) {
            return AvailabilityDashboardResult.NotFound(
                "poi_not_found",
                "no campsites for poi $id",
            )
        }
        val campsiteIds = poiCampsites.map { it.id }
        val dates =
            explicitDates.ifEmpty {
                campsiteIds
                    .flatMap { csId ->
                        availability.datesWithSnapshotsInWindow(campsiteId = csId)
                    }.distinct()
                    .sorted()
            }
        val stats =
            campsiteIds.flatMap { csId ->
                availability.projectAvailabilityRuns(csId, dates)
            }
        val timeRange = runs.timeRangeForPoi(id)
        val poiCadence = timeRange?.medianCadenceSec
        val aggregated =
            stats
                .groupBy { it.targetDate }
                .map { (date, group) ->
                    AvailabilityRepo.TargetDateStats(
                        targetDate = date,
                        totalRuns = timeRange?.totalRuns ?: 0,
                        firstRunAt = timeRange?.firstStartedAt,
                        lastRunAt = timeRange?.lastStartedAt,
                        medianCadenceSec = poiCadence,
                        lastOpenAt = group.mapNotNull { it.lastOpenAt }.maxOrNull(),
                        isCurrentlyOpen = group.any { it.isCurrentlyOpen },
                        minOpenWindowSec = group.mapNotNull { it.minOpenWindowSec }.minOrNull(),
                        maxOpenWindowSec = group.mapNotNull { it.maxOpenWindowSec }.maxOrNull(),
                    )
                }.sortedBy { it.targetDate }
        return AvailabilityDashboardResult.Ok(
            AvailabilitySnapshotsSummaryResponse(
                poiId = id,
                stats = aggregated.map { it.toSchema() },
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

private fun AvailabilityRepo.StatusRun.toSchema(name: String? = null): AvailabilityChangeSchema =
    AvailabilityChangeSchema(
        campsiteId = campsiteId,
        campsiteName = name,
        targetDate = targetDate.toString(),
        observedAt = fetchedAt.toString(),
        fromStatus = fromStatus,
        toStatus = toStatus,
    )

private fun campsiteDisplayName(cs: Campsite): String = if (cs.loopName != null) "${cs.loopName} / ${cs.name}" else cs.name

private fun AvailabilityRepo.TargetDateStats.toSchema(): AvailabilitySnapshotStatsSchema =
    AvailabilitySnapshotStatsSchema(
        targetDate = targetDate.toString(),
        totalRuns = totalRuns,
        firstRunAt = firstRunAt?.toString(),
        lastRunAt = lastRunAt?.toString(),
        medianCadenceSec = medianCadenceSec,
        lastOpenAt = lastOpenAt?.toString(),
        isCurrentlyOpen = isCurrentlyOpen,
        minOpenWindowSec = minOpenWindowSec,
        maxOpenWindowSec = maxOpenWindowSec,
    )
