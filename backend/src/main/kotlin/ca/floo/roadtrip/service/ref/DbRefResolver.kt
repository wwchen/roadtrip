package ca.floo.roadtrip.service.ref

import ca.floo.roadtrip.repo.RefLinkRepo
import kotlin.reflect.KClass

/**
 * The DB-backed resolution matrix: for each `(from, to)` pair, which link query
 * answers it. The queries themselves live in [RefLinkRepo]; this class owns only
 * the matrix and the wrapping of results into [RefValue]s, so adding a ref kind
 * is a table-of-contents edit here plus one repo method.
 */
class DbRefResolver(
    private val linkRepo: RefLinkRepo,
) : RefResolver {
    @Suppress("UNCHECKED_CAST")
    override fun <T : RefValue> resolve(
        from: RefValue,
        to: KClass<T>,
    ): List<T> =
        when (from) {
            is RefValue.PoiId -> resolveFromPoi(from.id, to)
            is RefValue.CampgroundId -> resolveFromCampground(from.id, to)
            is RefValue.CampsiteId -> resolveFromCampsite(from.id, to)
            is RefValue.CampgroundDataRef -> resolveFromCampgroundDataRef(from, to)
            is RefValue.CampsiteDataRef -> resolveFromCampsiteDataRef(from, to)
            is RefValue.CampgroundBookingRef -> resolveFromCampgroundBookingRef(from, to)
            is RefValue.CampsiteBookingRef -> resolveFromCampsiteBookingRef(from, to)
        } as List<T>

    override fun <T : RefValue> resolve(
        from: List<RefValue>,
        to: KClass<T>,
    ): Map<RefValue, List<T>> = from.associateWith { resolve(it, to) }

    private fun resolveFromPoi(
        poiId: Long,
        to: KClass<*>,
    ): List<RefValue> =
        when (to) {
            RefValue.CampgroundId::class -> linkRepo.campgroundIdsForPoi(poiId).map(RefValue::CampgroundId)
            RefValue.CampsiteId::class -> linkRepo.campsiteIdsForPoi(poiId).map(RefValue::CampsiteId)
            RefValue.CampgroundBookingRef::class ->
                linkRepo.campgroundBookingRefsForPoi(poiId).map(RefValue::CampgroundBookingRef)

            RefValue.CampsiteBookingRef::class ->
                linkRepo.campsiteBookingRefsForPoi(poiId).map(RefValue::CampsiteBookingRef)

            else -> emptyList()
        }

    private fun resolveFromCampground(
        campgroundId: Long,
        to: KClass<*>,
    ): List<RefValue> =
        when (to) {
            RefValue.PoiId::class -> linkRepo.poiIdsForCampground(campgroundId).map(RefValue::PoiId)
            RefValue.CampsiteId::class -> linkRepo.campsiteIdsForCampground(campgroundId).map(RefValue::CampsiteId)
            RefValue.CampgroundBookingRef::class ->
                linkRepo.bookingRefsForCampground(campgroundId).map(RefValue::CampgroundBookingRef)

            RefValue.CampgroundDataRef::class ->
                linkRepo.dataRefsForCampground(campgroundId).map(RefValue::CampgroundDataRef)

            else -> emptyList()
        }

    private fun resolveFromCampsite(
        campsiteId: Long,
        to: KClass<*>,
    ): List<RefValue> =
        when (to) {
            RefValue.PoiId::class -> linkRepo.poiIdsForCampsite(campsiteId).map(RefValue::PoiId)
            RefValue.CampgroundId::class -> linkRepo.campgroundIdsForCampsite(campsiteId).map(RefValue::CampgroundId)
            RefValue.CampsiteBookingRef::class ->
                linkRepo.bookingRefsForCampsite(campsiteId).map(RefValue::CampsiteBookingRef)

            RefValue.CampgroundBookingRef::class ->
                linkRepo.parentCampgroundBookingRefsForCampsite(campsiteId).map(RefValue::CampgroundBookingRef)

            RefValue.CampsiteDataRef::class ->
                linkRepo.dataRefsForCampsite(campsiteId).map(RefValue::CampsiteDataRef)

            else -> emptyList()
        }

    private fun resolveFromCampgroundDataRef(
        from: RefValue.CampgroundDataRef,
        to: KClass<*>,
    ): List<RefValue> =
        when (to) {
            RefValue.CampgroundId::class -> linkRepo.campgroundIdsByDataRef(from.ref).map(RefValue::CampgroundId)
            else -> emptyList()
        }

    private fun resolveFromCampsiteDataRef(
        from: RefValue.CampsiteDataRef,
        to: KClass<*>,
    ): List<RefValue> =
        when (to) {
            RefValue.CampsiteId::class -> linkRepo.campsiteIdsByDataRef(from.ref).map(RefValue::CampsiteId)
            else -> emptyList()
        }

    private fun resolveFromCampgroundBookingRef(
        from: RefValue.CampgroundBookingRef,
        to: KClass<*>,
    ): List<RefValue> =
        when (to) {
            RefValue.CampgroundId::class -> linkRepo.campgroundIdsByBookingRef(from.ref).map(RefValue::CampgroundId)
            RefValue.CampsiteId::class -> linkRepo.campsiteIdsByCampgroundBookingRef(from.ref).map(RefValue::CampsiteId)
            else -> emptyList()
        }

    private fun resolveFromCampsiteBookingRef(
        from: RefValue.CampsiteBookingRef,
        to: KClass<*>,
    ): List<RefValue> =
        when (to) {
            RefValue.CampsiteId::class -> linkRepo.campsiteIdsByBookingRef(from.ref).map(RefValue::CampsiteId)
            else -> emptyList()
        }
}
