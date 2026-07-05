package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityWindows
import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.availability.ResolvedDateWindow
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.service.reservation.CatalogReservableRef
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Platform-level outcome of one group's upstream fetch. Derived from the
 *  typed [ReservationProviderError] the adapter throws; provider-agnostic. */
enum class FetchOutcome { OK, RATE_LIMITED, UPSTREAM_5XX, BLOCKED, OTHER }

/** Result of one (provider, parentRef, dateContext) group's fetch.
 *  [window] is null when the group had no future dates and was skipped
 *  (no upstream call, no error). [batch] is non-null iff outcome == OK. */
internal data class GroupFetchResult(
    val provider: ReservationProvider,
    val parentRef: ProviderRef,
    val dateContext: PoiDateContext,
    val reservables: List<Reservable>,
    val window: ResolvedDateWindow?,
    val batch: AvailabilityObservationBatch?,
    val outcome: FetchOutcome,
    val durationMs: Int,
    val error: String?,
    val providerError: ReservationProviderError? = null,
)

internal fun Reservable.toCatalogReservableRef(): CatalogReservableRef =
    CatalogReservableRef(
        rid = rid.encode(),
        vendorId = rid.vendorId,
        mapId = aspiraProviderRefLong("mapId"),
        resourceLocationId = aspiraProviderRefLong("resourceLocationId"),
    )

private fun Reservable.aspiraProviderRefLong(key: String): Long? = (providerRef as? JsonObject)?.get(key)?.jsonPrimitive?.longOrNull

internal fun ReservationProviderError.toFetchOutcome(): FetchOutcome =
    when (this) {
        is ReservationProviderError.RateLimited -> FetchOutcome.RATE_LIMITED
        is ReservationProviderError.UpstreamBlocked -> FetchOutcome.BLOCKED
        is ReservationProviderError.UpstreamUnavailable -> FetchOutcome.UPSTREAM_5XX
        else -> FetchOutcome.OTHER
    }

internal class CatalogAvailabilityBatcher {
    private data class GroupKey(
        val provider: ReservationProvider,
        val parentRef: ProviderRef,
        val dateContext: PoiDateContext,
    )

    /**
     * How many distinct (provider, parentRef, dateContext) groups [targets]
     * would produce a REAL upstream call for — i.e. groups whose polling
     * window ([windowFor]) is non-null. Groups with a null window are skipped
     * by [fetchByGroup] (all target dates elapsed: no upstream call, no error),
     * so they must NOT be counted for the governor: charging a token for a
     * non-fetch wastes it and can needlessly starve a bucket, delaying the
     * retirement of an all-elapsed poller.
     *
     * The governor consumes one vendor token per REAL fetch group, so this is
     * the token count the executor must acquire before fetching. Uses the same
     * [GroupKey] and the same [windowFor] as [fetchByGroup] so the two never
     * drift on either the grouping key or the skip decision.
     */
    fun countFetchGroups(
        targets: List<ResolvedAvailabilityTarget>,
        windowFor: (PoiDateContext, ReservationProviderCapabilities) -> AvailabilityWindows?,
    ): Int =
        targets
            .map { GroupKey(it.provider, it.parentRef, it.dateContext) }
            .distinct()
            .count { windowFor(it.dateContext, it.provider.capabilities) != null }

    suspend fun fetchByGroup(
        targets: List<ResolvedAvailabilityTarget>,
        windowFor: (PoiDateContext, ReservationProviderCapabilities) -> AvailabilityWindows?,
        fetch: suspend (
            parentRef: ProviderRef,
            provider: ReservationProvider,
            reservables: List<Reservable>,
            windows: AvailabilityWindows,
        ) -> AvailabilityObservationBatch,
    ): List<GroupFetchResult> =
        targets
            .groupBy { GroupKey(it.provider, it.parentRef, it.dateContext) }
            .map { (key, groupTargets) ->
                val reservables = groupTargets.map { it.reservable }
                val windows = windowFor(key.dateContext, key.provider.capabilities)
                if (windows == null) {
                    return@map GroupFetchResult(
                        provider = key.provider,
                        parentRef = key.parentRef,
                        dateContext = key.dateContext,
                        reservables = reservables,
                        window = null,
                        batch = null,
                        outcome = FetchOutcome.OK,
                        durationMs = 0,
                        error = null,
                    )
                }
                val startedNanos = System.nanoTime()
                try {
                    val batch = fetch(key.parentRef, key.provider, reservables, windows)
                    GroupFetchResult(
                        provider = key.provider,
                        parentRef = key.parentRef,
                        dateContext = key.dateContext,
                        reservables = reservables,
                        window = windows.fetch,
                        batch = batch,
                        outcome = FetchOutcome.OK,
                        durationMs = elapsedMs(startedNanos),
                        error = null,
                    )
                } catch (e: ReservationProviderError) {
                    GroupFetchResult(
                        provider = key.provider,
                        parentRef = key.parentRef,
                        dateContext = key.dateContext,
                        reservables = reservables,
                        window = windows.fetch,
                        batch = null,
                        outcome = e.toFetchOutcome(),
                        durationMs = elapsedMs(startedNanos),
                        error = e.message ?: e::class.simpleName,
                        providerError = e,
                    )
                }
            }

    private fun elapsedMs(startedNanos: Long): Int = ((System.nanoTime() - startedNanos) / 1_000_000).toInt().coerceAtLeast(0)
}
