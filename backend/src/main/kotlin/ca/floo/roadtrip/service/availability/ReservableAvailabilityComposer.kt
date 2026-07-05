package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.models.api.AvailabilityResponseDto
import ca.floo.roadtrip.models.availability.AvailabilityWindows
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.service.api.AvailabilityLoader
import ca.floo.roadtrip.service.api.availabilityResponseFromObservations
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.time.LocalDate

private const val DEFAULT_AVAILABILITY_DAYS: Int = 7

/**
 * Composes per-reservable availability for a set of reservables that belong to
 * the same catalog (today: one POI's linked reservables). There is no
 * single-reservable entry point — availability is always requested for a
 * collection, so callers hand in the reservables they already loaded.
 *
 * Each reservable is resolved to its provider target, upstream calls are grouped
 * via [CatalogAvailabilityBatcher] (N reservables under one campground → one
 * upstream call), the [AvailabilityLoader] serves stored observations or goes
 * live, and each reservable's observations map to an [AvailabilityResponseDto]
 * in input order.
 *
 * [AvailabilityServiceImpl] owns this composer.
 */
internal class ReservableAvailabilityComposer(
    private val targets: AvailabilityTargetResolver,
    private val dateResolver: AvailabilityDateResolver = AvailabilityDateResolver(),
    availability: AvailabilityRepo? = null,
    private val snapshotFreshnessTtl: (ReservationProviderId) -> Duration = ::defaultSnapshotFreshnessTtl,
) {
    private val availabilityLoader = AvailabilityLoader(availability)

    suspend fun availabilityFor(
        reservables: List<Reservable>,
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): List<AvailabilityResponseDto> {
        if (reservables.isEmpty()) return emptyList()
        val resolved = reservables.map { targets.resolve(it) ?: throw AvailabilityServiceError.UnknownCampground }
        val byRid = linkedMapOf<String, AvailabilityResponseDto>()
        val batcher = CatalogAvailabilityBatcher()
        val results =
            batcher.fetchByGroup(
                targets = resolved,
                windowFor = { context, caps ->
                    val target =
                        dateResolver.resolveWindow(
                            startDate = startDate,
                            endDate = endDate,
                            context = context,
                            bookingHorizonDays = caps.bookingHorizonDays,
                            maxDays = caps.maxPollWindowDays,
                            defaultDays = DEFAULT_AVAILABILITY_DAYS,
                        )
                    val fetch =
                        dateResolver.wideWindow(
                            anchor = target.startDate,
                            context = context,
                            maxPollWindowDays = caps.maxPollWindowDays,
                            bookingHorizonDays = caps.bookingHorizonDays,
                        ) ?: target
                    AvailabilityWindows(target = target, fetch = fetch)
                },
                fetch = { parentRef, provider, rows, windows ->
                    availabilityLoader.loadOrFetch(
                        AvailabilityLoader.Request(
                            metadata = availabilityMetadata(provider.id, parentRef),
                            targets = rows.map { it.toAvailabilityTarget() },
                            startDate = windows.target.startDate,
                            endDate = windows.target.endDate,
                            ttl = snapshotFreshnessTtl(provider.id),
                        ),
                    ) {
                        provider.catalogAvailability(
                            CatalogAvailabilityRequest(
                                ref = parentRef,
                                reservables = rows.map { it.toCatalogReservableRef() },
                                startDate = windows.fetch.startDate,
                                endDate = windows.fetch.endDate,
                            ),
                        )
                    }
                },
            )
        // A group's provider error is swallowed-and-classified by the batcher so the
        // poller can record the failed run. On this live read path that classification
        // must not silently degrade into AvailabilityServiceError.NotFound (404, "stop
        // retrying") — rethrow so the route maps it to 503 (retryable), matching
        // pre-batching behavior.
        results.firstOrNull { it.providerError != null }?.let { throw it.providerError!! }
        results.forEach { result ->
            val batch = result.batch ?: return@forEach
            result.reservables.forEach { reservable ->
                val rid = reservable.rid.encode()
                val ref = reservable.providerRefForReservable(result.parentRef)
                val metadata = availabilityMetadata(result.provider.id, ref, reservableId = rid)
                byRid[rid] =
                    availabilityResponseFromObservations(
                        batch.copy(
                            observations = batch.observations.filter { it.reservableId == rid },
                            campgroundId = metadata.campgroundId ?: batch.campgroundId,
                            host = batch.host,
                            mapId = metadata.mapId ?: batch.mapId,
                            reservableId = rid,
                        ),
                    )
            }
        }
        return reservables.map { reservable ->
            byRid[reservable.rid.encode()] ?: throw AvailabilityServiceError.NotFound
        }
    }
}

internal fun defaultSnapshotFreshnessTtl(providerId: ReservationProviderId): Duration =
    when (providerId) {
        ReservationProviderId.RECGOV -> ApiCacheEntity.RECGOV_AVAILABILITY.defaultTtl
        ReservationProviderId.ASPIRA -> ApiCacheEntity.ASPIRA_AVAILABILITY.defaultTtl
        ReservationProviderId.RESERVEAMERICA -> ApiCacheEntity.RESERVEAMERICA_AVAILABILITY.defaultTtl
        ReservationProviderId.RESERVECALIFORNIA -> ApiCacheEntity.RESERVECALIFORNIA_AVAILABILITY.defaultTtl
    }

private fun Reservable.toAvailabilityTarget(): AvailabilityLoader.TargetReservable =
    AvailabilityLoader.TargetReservable(
        dbId = id,
        rid = rid.encode(),
    )

private fun availabilityMetadata(
    providerId: ReservationProviderId,
    ref: ProviderRef,
    reservableId: String? = null,
): AvailabilityLoader.Metadata =
    AvailabilityLoader.Metadata(
        provider = providerId.name.lowercase(),
        campgroundId = (ref as? ProviderRef.RecGov)?.recgovId,
        mapId =
            when (ref) {
                is ProviderRef.Aspira -> ref.mapId.toString()
                is ProviderRef.ReserveCalifornia -> ref.facilityIds.joinToString(",")
                else -> null
            },
        reservableId = reservableId,
    )

private fun Reservable.providerRefForReservable(parentRef: ProviderRef): ProviderRef =
    when (parentRef) {
        is ProviderRef.Aspira ->
            parentRef.copy(
                mapId = aspiraProviderRefLong("mapId") ?: parentRef.mapId,
                resourceLocationId = aspiraProviderRefLong("resourceLocationId") ?: parentRef.resourceLocationId,
            )
        else -> parentRef
    }

private fun Reservable.aspiraProviderRefLong(key: String): Long? =
    (providerRef as? JsonObject)
        ?.get(key)
        ?.jsonPrimitive
        ?.longOrNull
