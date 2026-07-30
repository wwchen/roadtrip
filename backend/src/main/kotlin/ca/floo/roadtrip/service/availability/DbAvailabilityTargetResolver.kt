package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.AvailabilityPollerConfig
import ca.floo.roadtrip.model.availability.AvailabilityWindows
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider

internal class DbAvailabilityTargetResolver(
    private val poiRepo: PoiRepo,
    private val campsitesRepo: CampsiteRepo,
    private val campgroundRepo: CampgroundRepo,
    private val availabilityProviders: List<AvailabilityProvider>,
    private val dateResolver: AvailabilityDateResolver,
    private val pollerRepo: AvailabilityPollerRepo,
    private val pollerConfig: AvailabilityPollerConfig = AvailabilityPollerConfig.default,
) : AvailabilityTargetResolver {
    override fun resolve(campsite: Campsite): ResolvedAvailabilityTarget? {
        val poiIds = campsitesRepo.poiIdsForCampsite(campsite.id)
        if (poiIds.isEmpty()) return null

        val candidates =
            poiIds.flatMap { poiId ->
                val campground = campgroundRepo.findByPoi(poiId) ?: return@flatMap emptyList()
                availabilityProviders
                    .filter { it.supportsCampground(campground) }
                    .map { provider -> Triple(poiId, campground, provider) }
            }

        val (poiId, campground, provider) = candidates.firstOrNull() ?: return null
        val poiCentroid = poiRepo.findCentroid(poiId)

        return ResolvedAvailabilityTarget(
            campsite = campsite,
            provider = provider,
            campground = campground,
            parentPoiId = poiId,
            dateContext = dateResolver.context(lat = poiCentroid?.lat, lng = poiCentroid?.lng),
            candidates = candidates.map { (_, _, prov) -> prov },
        )
    }

    override fun resolve(poller: AvailabilityPollerRepo.Poller): PollerFetchPlan? {
        val liveWatches = pollerRepo.liveWatchesForPoller(poller.id)
        if (liveWatches.isEmpty()) return null

        val poiCadenceOverrideSec = pollerRepo.cadenceOverrideForPoller(poller.id)
        val cadenceSec = resolveCadenceSec(liveWatches, poiCadenceOverrideSec, pollerConfig.defaultCadenceSec)

        val targets =
            campsitesRepo
                .findByPoi(poller.poiId)
                .mapNotNull { resolve(it) }
                .filter {
                    val ref = it.parentRef
                    ref != null &&
                        ref.parentRefKey == poller.parentRef &&
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
}
