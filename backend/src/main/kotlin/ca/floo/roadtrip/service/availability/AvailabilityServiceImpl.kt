package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.models.api.AvailabilityResponseDto
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.service.api.SnapshotBackedAvailabilityService
import ca.floo.roadtrip.service.api.availabilityResponseFromObservations
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.time.LocalDate

private const val MAX_AVAILABILITY_DAYS: Int = 60
private const val DEFAULT_AVAILABILITY_DAYS: Int = 7

internal class AvailabilityServiceImpl(
    private val targets: AvailabilityTargetResolver,
    private val dateResolver: AvailabilityDateResolver = AvailabilityDateResolver(),
    snapshots: AvailabilitySnapshotRepo? = null,
    private val snapshotFreshnessTtl: (ReservationProviderId) -> Duration = ::defaultSnapshotFreshnessTtl,
) : AvailabilityService {
    private val snapshotAvailability = SnapshotBackedAvailabilityService(snapshots)

    override suspend fun getByRid(
        rid: ReservableId,
        startDate: LocalDate?,
        endDate: LocalDate?,
        force: Boolean,
    ): AvailabilityResponseDto = getByRids(listOf(rid), startDate, endDate, force).single()

    override suspend fun getByRids(
        rids: List<ReservableId>,
        startDate: LocalDate?,
        endDate: LocalDate?,
        force: Boolean,
    ): List<AvailabilityResponseDto> {
        if (rids.isEmpty()) return emptyList()
        val resolved = rids.map { targets.requireByRid(it) }
        val byRid = linkedMapOf<String, AvailabilityResponseDto>()
        resolved
            .groupBy { AvailabilityFetchGroup(provider = it.provider, parentRef = it.parentRef, dateContext = it.dateContext) }
            .forEach { (group, items) ->
                val query =
                    dateResolver.resolveWindow(
                        startDate = startDate,
                        endDate = endDate,
                        context = group.dateContext,
                        bookingHorizonDays = group.provider.capabilities.bookingHorizonDays,
                        maxDays = MAX_AVAILABILITY_DAYS,
                        defaultDays = DEFAULT_AVAILABILITY_DAYS,
                    )
                fetchCatalogReservablesAvailability(
                    catalogRows = items.map { it.reservable },
                    parentRef = group.parentRef,
                    provider = group.provider,
                    startDate = query.startDate,
                    endDate = query.endDate,
                    force = force,
                ).forEach { response ->
                    response.reservableId?.let { byRid[it] = response }
                }
            }
        return rids.map { rid ->
            byRid[rid.encode()] ?: throw AvailabilityServiceError.NotFound
        }
    }

    private suspend fun fetchCatalogReservablesAvailability(
        catalogRows: List<Reservable>,
        parentRef: ProviderRef,
        provider: ReservationProvider,
        startDate: LocalDate,
        endDate: LocalDate,
        force: Boolean,
    ): List<AvailabilityResponseDto> {
        val batch =
            fetchCatalogAvailabilityBatch(
                catalogRows = catalogRows,
                parentRef = parentRef,
                provider = provider,
                startDate = startDate,
                endDate = endDate,
                force = force,
            )
        return catalogRows.map { reservable ->
            val rid = reservable.rid.encode()
            val ref = reservable.providerRefForReservable(parentRef)
            val metadata = availabilityMetadata(provider.id, ref, reservableId = rid)
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

    private suspend fun fetchCatalogAvailabilityBatch(
        catalogRows: List<Reservable>,
        parentRef: ProviderRef,
        provider: ReservationProvider,
        startDate: LocalDate,
        endDate: LocalDate,
        force: Boolean,
    ): AvailabilityObservationBatch =
        snapshotAvailability.loadOrFetch(
            SnapshotBackedAvailabilityService.Request(
                metadata = availabilityMetadata(provider.id, parentRef),
                targets = catalogRows.map { it.toAvailabilityTarget() },
                startDate = startDate,
                endDate = endDate,
                ttl = snapshotFreshnessTtl(provider.id),
                force = force,
            ),
        ) {
            provider.catalogAvailability(
                CatalogAvailabilityRequest(
                    ref = parentRef,
                    reservables = catalogRows.map { it.toCatalogReservableRef() },
                    startDate = startDate,
                    endDate = endDate,
                    force = force,
                ),
            )
        }
}

private data class AvailabilityFetchGroup(
    val provider: ReservationProvider,
    val parentRef: ProviderRef,
    val dateContext: PoiDateContext,
)

internal fun defaultSnapshotFreshnessTtl(providerId: ReservationProviderId): Duration =
    when (providerId) {
        ReservationProviderId.RECGOV -> ApiCacheEntity.RECGOV_AVAILABILITY.defaultTtl
        ReservationProviderId.ASPIRA -> ApiCacheEntity.ASPIRA_AVAILABILITY.defaultTtl
        ReservationProviderId.RESERVEAMERICA -> ApiCacheEntity.RESERVEAMERICA_AVAILABILITY.defaultTtl
        ReservationProviderId.RESERVECALIFORNIA -> ApiCacheEntity.RESERVECALIFORNIA_AVAILABILITY.defaultTtl
    }

private fun Reservable.toAvailabilityTarget(): SnapshotBackedAvailabilityService.TargetReservable =
    SnapshotBackedAvailabilityService.TargetReservable(
        dbId = id,
        rid = rid.encode(),
    )

private fun availabilityMetadata(
    providerId: ReservationProviderId,
    ref: ProviderRef,
    reservableId: String? = null,
): SnapshotBackedAvailabilityService.Metadata =
    SnapshotBackedAvailabilityService.Metadata(
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
