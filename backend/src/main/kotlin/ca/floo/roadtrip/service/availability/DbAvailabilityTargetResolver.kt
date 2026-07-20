package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityWindows
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import org.jooq.DSLContext

internal class DbAvailabilityTargetResolver(
    private val ctx: DSLContext,
    private val campsitesRepo: CampsiteRepo,
    private val campgroundRepo: CampgroundRepo,
    private val availabilityProviders: List<AvailabilityProvider>,
    private val dateResolver: AvailabilityDateResolver,
    private val pollerRepo: AvailabilityPollerRepo,
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
        val poiLatLng = getPoiLatLng(poiId)

        return ResolvedAvailabilityTarget(
            campsite = campsite,
            provider = provider,
            campground = campground,
            parentPoiId = poiId,
            dateContext = dateResolver.context(lat = poiLatLng?.first, lng = poiLatLng?.second),
            candidates = candidates.map { (_, _, prov) -> prov },
        )
    }

    override fun resolve(poller: AvailabilityPollerRepo.Poller): PollerFetchPlan? {
        val liveWatches = pollerRepo.liveWatchesForPoller(poller.id)
        if (liveWatches.isEmpty()) return null

        val poiCadenceOverrideSec = pollerRepo.cadenceOverrideForPoller(poller.id)
        val cadenceSec = resolveCadenceSec(liveWatches, poiCadenceOverrideSec)

        val targets =
            campsitesRepo
                .findByPoi(poller.poiId)
                .mapNotNull { resolve(it) }
                .filter {
                    val ref = it.parentRef
                    ref != null &&
                        parentRefKey(ref) == poller.parentRef &&
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

    private fun getPoiLatLng(poiId: Long): Pair<Double, Double>? {
        val r =
            ctx
                .fetchOne(
                    """
                    SELECT ST_X(ST_PointOnSurface(geom)) AS lng,
                           ST_Y(ST_PointOnSurface(geom)) AS lat
                    FROM pois
                    WHERE id = ?
                    """.trimIndent(),
                    poiId,
                ) ?: return null
        val lng = r.get("lng") as? Number ?: return null
        val lat = r.get("lat") as? Number ?: return null
        return lat.toDouble() to lng.toDouble()
    }
}
