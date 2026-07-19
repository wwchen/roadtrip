package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityWindows
import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.domain.BookingRef
import ca.floo.roadtrip.model.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.model.domain.CampsiteProviderRefRow
import ca.floo.roadtrip.model.domain.ProviderRef
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.availability.provider.ProviderRefParser
import ca.floo.roadtrip.service.availability.provider.availabilityProviderId

internal class DbAvailabilityTargetResolver(
    private val campsiteProviderRepo: CampsiteProviderRepo,
    private val campsitesRepo: CampsiteRepo,
    private val availabilityProviders: AvailabilityProviderRegistry,
    private val dateResolver: AvailabilityDateResolver,
    private val pollerRepo: AvailabilityPollerRepo,
) : AvailabilityTargetResolver {
    private data class ResolvedRow(
        val poiId: Long,
        val row: CampsiteProviderRefRow,
        val candidate: ProviderCandidate,
    )

    override fun resolve(campsite: CampsiteAvailabilityTarget): ResolvedAvailabilityTarget? {
        val poiIds = campsitesRepo.poiIdsForCampsite(campsite.id)
        if (poiIds.isEmpty()) return null

        val providerRefsByPoiId = campsiteProviderRepo.findProviderRefCandidates(poiIds)
        val resolvedRows: List<ResolvedRow> =
            poiIds
                .asSequence()
                .flatMap { poiId -> providerRefsByPoiId[poiId].orEmpty().asSequence().map { row -> poiId to row } }
                .mapNotNull { (poiId, row) ->
                    val candidate = buildCandidate(row, campsite) ?: return@mapNotNull null
                    ResolvedRow(poiId = poiId, row = row, candidate = candidate)
                }.toList()

        val head = resolvedRows.firstOrNull() ?: return null

        return ResolvedAvailabilityTarget(
            campsite = campsite,
            provider = head.candidate.provider,
            parentRef = head.candidate.parentRef,
            catalogRef = head.candidate.catalogRef,
            parentPoiId = head.poiId,
            dateContext = dateResolver.context(lat = head.row.lat, lng = head.row.lng),
            candidates = resolvedRows.map { it.candidate },
        )
    }

    override fun resolve(poller: AvailabilityPollerRepo.Poller): PollerFetchPlan? {
        val liveWatches = pollerRepo.liveWatchesForPoller(poller.id)
        if (liveWatches.isEmpty()) return null

        val poiCadenceOverrideSec = pollerRepo.cadenceOverrideForPoller(poller.id)
        val cadenceSec = resolveCadenceSec(liveWatches, poiCadenceOverrideSec)

        val targets =
            campsitesRepo
                .findAvailabilityTargetsByPoi(poller.poiId)
                .mapNotNull { resolve(it) }
                .filter {
                    parentRefKey(it.parentRef) == poller.parentRef &&
                        it.provider.id.name
                            .lowercase() == poller.provider
                }.distinctBy { it.campsite.id }

        val windowFor = {
                context: ca.floo.roadtrip.model.availability.PoiDateContext,
                caps: ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities,
            ->
            val resolvedWindow =
                dateResolver.resolvePollingWindow(
                    context = context,
                    maxPollWindowDays = caps.maxPollWindowDays,
                    bookingHorizonDays = caps.bookingHorizonDays,
                )
            resolvedWindow?.let {
                AvailabilityWindows(target = it, fetch = it)
            }
        }

        return PollerFetchPlan(
            targets = targets,
            windowFor = windowFor,
            cadenceSec = cadenceSec,
            liveWatches = liveWatches,
        )
    }

    private fun buildCandidate(
        row: CampsiteProviderRefRow,
        campsite: CampsiteAvailabilityTarget,
    ): ProviderCandidate? {
        val (ref, provider) = resolveFromBookingRef(row) ?: resolveFromLegacyJson(row) ?: return null
        return ProviderCandidate(
            provider = provider,
            parentRef = ref,
            catalogRef = catalogRefFor(campsite, provider.id),
        )
    }

    private fun resolveFromBookingRef(row: CampsiteProviderRefRow): Pair<ProviderRef, AvailabilityProvider>? {
        val bp = row.bookingProvider ?: return null
        val bpRef = row.bookingProviderRef ?: return null
        val bookingRef = BookingRef.parse(bp, bpRef) ?: return null
        val provider = availabilityProviders.forBooking(bp, bookingRef) ?: return null
        return bookingRef.toProviderRef() to provider
    }

    private fun resolveFromLegacyJson(row: CampsiteProviderRefRow): Pair<ProviderRef, AvailabilityProvider>? {
        val ref = ProviderRefParser.parse(row.providerRefJson) ?: return null
        val provider = availabilityProviders.forPoi(row, ref) ?: return null
        return ref to provider
    }

    private fun catalogRefFor(
        campsite: CampsiteAvailabilityTarget,
        providerId: AvailabilityProviderId,
    ): CatalogCampsiteRef {
        val fallback = campsite.toCatalogCampsiteRef()
        val ref =
            campsiteProviderRepo
                .findCampsiteProviderRefs(campsite.id)
                .asSequence()
                .mapNotNull { ProviderRefParser.parse(it.providerRefJson) }
                .firstOrNull { it.availabilityProviderId() == providerId }
                ?: return fallback
        return ref.toCatalogCampsiteRef(campsiteId = campsite.id, fallback = fallback)
    }

    private fun ProviderRef.toCatalogCampsiteRef(
        campsiteId: Long,
        fallback: CatalogCampsiteRef,
    ): CatalogCampsiteRef =
        when (this) {
            is ProviderRef.RecGov ->
                CatalogCampsiteRef(
                    campsiteId = campsiteId,
                    vendorId = recgovId,
                )
            is ProviderRef.Campflare ->
                CatalogCampsiteRef(
                    campsiteId = campsiteId,
                    vendorId = campgroundId,
                )
            is ProviderRef.Aspira ->
                fallback.copy(
                    campsiteId = campsiteId,
                    mapId = mapId,
                    resourceLocationId = resourceLocationId,
                )
            else -> fallback.copy(campsiteId = campsiteId)
        }
}
