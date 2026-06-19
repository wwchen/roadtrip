package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.api.AvailabilityObservationBatch
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.api.SnapshotBackedAvailabilityService
import ca.floo.roadtrip.service.api.availabilityResponseFromObservations
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.reservation.CatalogReservableRef
import ca.floo.roadtrip.service.reservation.ProviderRefParser
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val MAX_AVAILABILITY_DAYS: Int = 60

class AvailabilityServiceImpl(
    private val providerRefs: CampsiteProviderRepo,
    private val reservationProviders: ReservationProviderRegistry,
    private val reservablesRepo: ReservableRepo,
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
        val resolved = rids.map { resolveReservable(it) }
        val byRid = linkedMapOf<String, AvailabilityResponseDto>()
        resolved
            .groupBy { AvailabilityFetchGroup(provider = it.provider, parentRef = it.parentRef) }
            .forEach { (group, items) ->
                val query =
                    resolveAvailabilityWindow(startDate, endDate, force, group.provider.capabilities.bookingHorizonDays)
                        ?: throw AvailabilityServiceError.BadDateWindow
                fetchCatalogReservablesAvailability(
                    catalogRows = items.map { it.reservable },
                    parentRef = group.parentRef,
                    provider = group.provider,
                    startDate = query.startDate,
                    endDate = query.endDate,
                    force = query.force,
                ).forEach { response ->
                    response.reservableId?.let { byRid[it] = response }
                }
            }
        return rids.map { rid ->
            byRid[rid.encode()] ?: throw AvailabilityServiceError.NotFound
        }
    }

    private fun resolveReservable(rid: ReservableId): ResolvedReservable {
        val reservable =
            reservablesRepo.findByRid(rid)
                ?: throw AvailabilityServiceError.NotFound
        val poiIds = reservablesRepo.poiIdsForReservable(reservable.id)
        val providerRefsByPoiId = providerRefs.findProviderRefs(poiIds)
        val parent =
            poiIds
                .asSequence()
                .mapNotNull { providerRefsByPoiId[it] }
                .firstOrNull { reservationProviders.forPoi(it) != null && ProviderRefParser.parse(it.providerRefJson) != null }
                ?: throw AvailabilityServiceError.UnknownCampground
        val provider = reservationProviders.forPoi(parent)!!
        val parentRef = ProviderRefParser.parse(parent.providerRefJson)!!
        return ResolvedReservable(
            reservable = reservable,
            provider = provider,
            parentRef = parentRef,
        )
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

private data class ResolvedReservable(
    val reservable: Reservable,
    val provider: ReservationProvider,
    val parentRef: ProviderRef,
)

private data class AvailabilityFetchGroup(
    val provider: ReservationProvider,
    val parentRef: ProviderRef,
)

internal sealed class StartParam {
    data class Ok(
        val value: LocalDate,
    ) : StartParam()

    object Invalid : StartParam()
}

internal fun parseStartParam(
    raw: LocalDate?,
    today: LocalDate,
    horizonDays: Int,
): StartParam {
    if (raw == null) return StartParam.Ok(today)
    if (raw.isBefore(today)) return StartParam.Invalid
    if (raw.isAfter(today.plusDays(horizonDays.toLong()))) return StartParam.Invalid
    return StartParam.Ok(raw)
}

private data class ResolvedAvailabilityWindow(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val force: Boolean,
)

private fun resolveAvailabilityWindow(
    startDate: LocalDate?,
    endDate: LocalDate?,
    force: Boolean,
    bookingHorizonDays: Int,
    defaultDays: Int = 7,
): ResolvedAvailabilityWindow? {
    val today = LocalDate.now(ZoneId.systemDefault())
    val start =
        when (val parsed = parseStartParam(startDate, today, bookingHorizonDays)) {
            is StartParam.Ok -> parsed.value
            StartParam.Invalid -> return null
        }
    val end = endDate ?: start.plusDays(defaultDays.toLong())
    if (!end.isAfter(start)) return null
    if (end.isAfter(today.plusDays(bookingHorizonDays.toLong()))) return null
    val days = ChronoUnit.DAYS.between(start, end).toInt()
    if (days !in 1..MAX_AVAILABILITY_DAYS) return null
    return ResolvedAvailabilityWindow(startDate = start, endDate = end, force = force)
}

internal fun defaultSnapshotFreshnessTtl(providerId: ReservationProviderId): Duration =
    when (providerId) {
        ReservationProviderId.RECGOV -> ApiCacheEntity.RECGOV_AVAILABILITY.defaultTtl
        ReservationProviderId.ASPIRA -> ApiCacheEntity.ASPIRA_AVAILABILITY.defaultTtl
        ReservationProviderId.CAMIS -> ApiCacheEntity.RECGOV_AVAILABILITY.defaultTtl
    }

private fun Reservable.toCatalogReservableRef(): CatalogReservableRef =
    CatalogReservableRef(
        rid = rid.encode(),
        vendorId = rid.vendorId,
        mapId = aspiraProviderRefLong("mapId"),
        resourceLocationId = aspiraProviderRefLong("resourceLocationId"),
    )

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
        mapId = (ref as? ProviderRef.Aspira)?.mapId?.toString(),
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
