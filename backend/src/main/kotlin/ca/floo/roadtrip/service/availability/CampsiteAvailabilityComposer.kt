package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.model.api.AvailabilityResponseDto
import ca.floo.roadtrip.model.availability.AvailabilityWindows
import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.service.api.AvailabilityLoader
import ca.floo.roadtrip.service.api.availabilityResponseFromObservations
import java.time.Duration
import java.time.LocalDate

private const val DEFAULT_AVAILABILITY_DAYS: Int = 7

internal class CampsiteAvailabilityComposer(
    private val targets: AvailabilityTargetResolver,
    private val dateResolver: AvailabilityDateResolver,
    availabilityRepo: AvailabilityRepo? = null,
    private val snapshotFreshnessTtl: (BookingProvider) -> Duration = ::defaultSnapshotFreshnessTtl,
    private val failoverFetcher: FailoverAvailabilityFetcher,
) {
    private val availabilityLoader = AvailabilityLoader(availabilityRepo)

    suspend fun availabilityFor(
        campsites: List<Campsite>,
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): List<AvailabilityResponseDto> {
        if (campsites.isEmpty()) return emptyList()
        val resolved = campsites.map { targets.resolve(it) ?: throw AvailabilityServiceError.UnknownCampground }
        val byCampsiteId = linkedMapOf<Long, AvailabilityResponseDto>()
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
                        fetchWithFailover(rows, windows)
                    }
                },
            )
        val catalogRefByCampsiteId = resolved.associate { it.campsite.id to it.catalogRef }
        results.firstOrNull { it.providerError != null }?.let { throw it.providerError!! }
        results.forEach { result ->
            val batch = result.batch ?: return@forEach
            result.campsites.forEach { campsite ->
                val catalogRef = catalogRefByCampsiteId[campsite.id]
                val ref = catalogRef?.toProviderRef(result.parentRef) ?: result.parentRef
                val metadata = availabilityMetadata(result.provider.id, ref, campsiteId = campsite.id)
                byCampsiteId[campsite.id] =
                    availabilityResponseFromObservations(
                        batch.copy(
                            observations = batch.observations.filter { it.campsiteId == campsite.id },
                            campgroundId = metadata.campgroundId ?: batch.campgroundId,
                            host = metadata.host ?: batch.host,
                            mapId = metadata.mapId ?: batch.mapId,
                            campsiteId = campsite.id,
                        ),
                    )
            }
        }
        return campsites.map { campsite ->
            byCampsiteId[campsite.id] ?: throw AvailabilityServiceError.NotFound
        }
    }

    /**
     * Runs the group's fetch through [failoverFetcher]. Preferred candidate
     * refs come from the batcher key; alternate candidates are resolved from
     * each row's own candidate list, so observations stay anchored to the
     * requested campsite ids without any cross-row identity translation.
     */
    private suspend fun fetchWithFailover(
        rows: List<ResolvedAvailabilityTarget>,
        windows: AvailabilityWindows,
    ): ca.floo.roadtrip.model.availability.AvailabilityObservationBatch {
        val groupCandidates = rows.first().candidates
        val preferredRefs = rows.map { it.catalogRef }
        val result =
            failoverFetcher.fetch(
                candidates = groupCandidates,
                campsites = rows.map { it.campsite },
                window = ResolvedDateWindow(windows.fetch.startDate, windows.fetch.endDate),
                translateRefs = { candidate ->
                    if (candidate === groupCandidates.first()) {
                        preferredRefs
                    } else {
                        rows.catalogRefsFor(candidate)
                    }
                },
            )
        return result.batch ?: throw availabilityProviderErrorFromAttempt(result.attempts.lastOrNull())
    }
}

internal fun defaultSnapshotFreshnessTtl(providerId: BookingProvider): Duration =
    when (providerId) {
        BookingProvider.RECGOV -> ApiCacheEntity.RECGOV_AVAILABILITY.defaultTtl
        BookingProvider.CAMPFLARE -> ApiCacheEntity.CAMPFLARE_AVAILABILITY.defaultTtl
        BookingProvider.ASPIRA -> ApiCacheEntity.ASPIRA_AVAILABILITY.defaultTtl
        BookingProvider.RESERVEAMERICA -> ApiCacheEntity.RESERVEAMERICA_AVAILABILITY.defaultTtl
        BookingProvider.RESERVECALIFORNIA -> ApiCacheEntity.RESERVECALIFORNIA_AVAILABILITY.defaultTtl
    }

private fun ResolvedAvailabilityTarget.toAvailabilityTarget(): AvailabilityLoader.CampsiteTarget =
    AvailabilityLoader.CampsiteTarget(
        dbId = campsite.id,
    )

private fun availabilityMetadata(
    providerId: BookingProvider,
    ref: BookingProviderRef,
    campsiteId: Long? = null,
): AvailabilityLoader.Metadata =
    AvailabilityLoader.Metadata(
        provider = providerId.id,
        campgroundId =
            when (ref) {
                is BookingProviderRef.RecGov -> ref.facilityId
                is BookingProviderRef.Campflare -> ref.campgroundId
                else -> null
            },
        mapId =
            when (ref) {
                is BookingProviderRef.Aspira -> ref.mapId.toString()
                is BookingProviderRef.ReserveCalifornia -> ref.facilityIds.joinToString(",")
                else -> null
            },
        campsiteId = campsiteId,
    )

private fun CatalogCampsiteRef.toProviderRef(parentRef: BookingProviderRef): BookingProviderRef =
    when (parentRef) {
        is BookingProviderRef.Aspira ->
            parentRef.copy(
                mapId = mapId ?: parentRef.mapId,
                resourceLocationId = resourceLocationId ?: parentRef.resourceLocationId,
            )
        else -> parentRef
    }
