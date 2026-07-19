package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityWindows
import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.ref.RefResolver
import ca.floo.roadtrip.service.ref.RefValue
import ca.floo.roadtrip.service.ref.resolve
import org.jooq.DSLContext

internal class DbAvailabilityTargetResolver(
    private val refResolver: RefResolver,
    private val ctx: DSLContext,
    private val campsitesRepo: CampsiteRepo,
    private val availabilityProviders: AvailabilityProviderRegistry,
    private val dateResolver: AvailabilityDateResolver,
    private val pollerRepo: AvailabilityPollerRepo,
) : AvailabilityTargetResolver {
    override fun resolve(campsite: CampsiteAvailabilityTarget): ResolvedAvailabilityTarget? {
        val poiIds = campsitesRepo.poiIdsForCampsite(campsite.id)
        if (poiIds.isEmpty()) return null

        val candidates =
            poiIds.flatMap { poiId ->
                val bookingRefs = refResolver.resolve<RefValue.CampgroundBookingRef>(RefValue.PoiId(poiId))
                bookingRefs.mapNotNull { bookingRefValue ->
                    val provider =
                        availabilityProviders.forBooking(bookingRefValue.ref.provider, bookingRefValue.ref)
                            ?: return@mapNotNull null
                    Triple(poiId, bookingRefValue.ref, provider)
                }
            }

        val (poiId, parentRef, provider) = candidates.firstOrNull() ?: return null
        val catalogRef = buildCatalogRef(campsite, parentRef)
        val poiLatLng = getPoiLatLng(poiId)

        return ResolvedAvailabilityTarget(
            campsite = campsite,
            provider = provider,
            parentRef = parentRef,
            catalogRef = catalogRef,
            parentPoiId = poiId,
            dateContext = dateResolver.context(lat = poiLatLng?.first, lng = poiLatLng?.second),
            candidates =
                candidates.map { (_, ref, prov) ->
                    ProviderCandidate(
                        provider = prov,
                        parentRef = ref,
                        catalogRef = buildCatalogRef(campsite, ref),
                    )
                },
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

    private fun buildCatalogRef(
        campsite: CampsiteAvailabilityTarget,
        parentRef: BookingProviderRef,
    ): CatalogCampsiteRef {
        val campsiteRef =
            refResolver
                .resolve<RefValue.CampsiteBookingRef>(RefValue.CampsiteId(campsite.id))
                .firstOrNull { it.ref.provider == parentRef.provider }
                ?.ref
        return when (parentRef) {
            is BookingProviderRef.RecGov ->
                CatalogCampsiteRef(
                    campsiteId = campsite.id,
                    vendorId = (campsiteRef as? BookingProviderRef.RecGov)?.facilityId ?: campsite.vendorId,
                )
            is BookingProviderRef.Campflare ->
                CatalogCampsiteRef(
                    campsiteId = campsite.id,
                    vendorId = (campsiteRef as? BookingProviderRef.Campflare)?.campgroundId ?: campsite.vendorId,
                )
            is BookingProviderRef.Aspira ->
                CatalogCampsiteRef(
                    campsiteId = campsite.id,
                    vendorId = campsite.vendorId,
                    mapId = (campsiteRef as? BookingProviderRef.Aspira)?.mapId ?: parentRef.mapId,
                    resourceLocationId = (campsiteRef as? BookingProviderRef.Aspira)?.resourceLocationId ?: parentRef.resourceLocationId,
                )
            is BookingProviderRef.ReserveAmerica ->
                CatalogCampsiteRef(
                    campsiteId = campsite.id,
                    vendorId = campsite.vendorId,
                )
            is BookingProviderRef.ReserveCalifornia ->
                CatalogCampsiteRef(
                    campsiteId = campsite.id,
                    vendorId = campsite.vendorId,
                )
        }
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
