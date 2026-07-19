package ca.floo.roadtrip.service.ref

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import org.jooq.DSLContext
import kotlin.reflect.KClass

class DbRefResolver(
    private val ctx: DSLContext,
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
            is RefValue.CampgroundDataRef -> resolveFromCampgroundDataRef(from.ref, to)
            is RefValue.CampsiteDataRef -> resolveFromCampsiteDataRef(from.ref, to)
            is RefValue.CampgroundBookingRef -> resolveFromCampgroundBookingRef(from.ref, to)
            is RefValue.CampsiteBookingRef -> resolveFromCampsiteBookingRef(from.ref, to)
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
            RefValue.CampgroundId::class ->
                ctx
                    .fetch(
                        """
                        SELECT pc.campground_id
                        FROM poi_campgrounds pc
                        JOIN campgrounds cg ON cg.id = pc.campground_id
                        WHERE pc.poi_id = ? AND cg.deleted_at IS NULL
                        """.trimIndent(),
                        poiId,
                    ).map { RefValue.CampgroundId(it.get("campground_id", Long::class.java)) }

            RefValue.CampsiteId::class ->
                ctx
                    .fetch(
                        """
                        SELECT c.id
                        FROM campsites c
                        JOIN poi_campgrounds pc ON pc.campground_id = c.campground_id
                        WHERE pc.poi_id = ? AND c.deleted_at IS NULL
                        """.trimIndent(),
                        poiId,
                    ).map { RefValue.CampsiteId(it.get("id", Long::class.java)) }

            RefValue.CampgroundBookingRef::class ->
                ctx
                    .fetch(
                        """
                        SELECT cg.booking_provider, cg.booking_provider_ref
                        FROM campgrounds cg
                        JOIN poi_campgrounds pc ON pc.campground_id = cg.id
                        WHERE pc.poi_id = ?
                          AND cg.deleted_at IS NULL
                          AND cg.booking_provider IS NOT NULL
                        """.trimIndent(),
                        poiId,
                    ).mapNotNull(::parseCampgroundBookingRef)

            RefValue.CampsiteBookingRef::class ->
                ctx
                    .fetch(
                        """
                        SELECT c.booking_provider, c.booking_provider_ref
                        FROM campsites c
                        JOIN poi_campgrounds pc ON pc.campground_id = c.campground_id
                        WHERE pc.poi_id = ?
                          AND c.deleted_at IS NULL
                          AND c.booking_provider IS NOT NULL
                        """.trimIndent(),
                        poiId,
                    ).mapNotNull(::parseCampsiteBookingRef)

            else -> emptyList()
        }

    private fun resolveFromCampground(
        campgroundId: Long,
        to: KClass<*>,
    ): List<RefValue> =
        when (to) {
            RefValue.PoiId::class ->
                ctx
                    .fetch(
                        """
                        SELECT pc.poi_id
                        FROM poi_campgrounds pc
                        JOIN pois p ON p.id = pc.poi_id
                        WHERE pc.campground_id = ? AND p.deleted_at IS NULL
                        """.trimIndent(),
                        campgroundId,
                    ).map { RefValue.PoiId(it.get("poi_id", Long::class.java)) }

            RefValue.CampsiteId::class ->
                ctx
                    .fetch(
                        """
                        SELECT c.id FROM campsites c
                        WHERE c.campground_id = ? AND c.deleted_at IS NULL
                        """.trimIndent(),
                        campgroundId,
                    ).map { RefValue.CampsiteId(it.get("id", Long::class.java)) }

            RefValue.CampgroundBookingRef::class ->
                ctx
                    .fetch(
                        """
                        SELECT cg.booking_provider, cg.booking_provider_ref
                        FROM campgrounds cg
                        WHERE cg.id = ? AND cg.deleted_at IS NULL AND cg.booking_provider IS NOT NULL
                        """.trimIndent(),
                        campgroundId,
                    ).mapNotNull(::parseCampgroundBookingRef)

            RefValue.CampgroundDataRef::class ->
                ctx
                    .fetch(
                        """
                        SELECT cg.data_provider, cg.data_provider_ref
                        FROM campgrounds cg
                        WHERE cg.id = ? AND cg.deleted_at IS NULL
                        """.trimIndent(),
                        campgroundId,
                    ).mapNotNull(::parseCampgroundDataRef)

            else -> emptyList()
        }

    private fun resolveFromCampsite(
        campsiteId: Long,
        to: KClass<*>,
    ): List<RefValue> =
        when (to) {
            RefValue.PoiId::class ->
                ctx
                    .fetch(
                        """
                        SELECT pc.poi_id
                        FROM campsites c
                        JOIN poi_campgrounds pc ON pc.campground_id = c.campground_id
                        JOIN pois p ON p.id = pc.poi_id
                        WHERE c.id = ? AND c.deleted_at IS NULL AND p.deleted_at IS NULL
                        """.trimIndent(),
                        campsiteId,
                    ).map { RefValue.PoiId(it.get("poi_id", Long::class.java)) }

            RefValue.CampgroundId::class ->
                ctx
                    .fetch(
                        """
                        SELECT c.campground_id FROM campsites c
                        WHERE c.id = ? AND c.deleted_at IS NULL
                        """.trimIndent(),
                        campsiteId,
                    ).map { RefValue.CampgroundId(it.get("campground_id", Long::class.java)) }

            RefValue.CampsiteBookingRef::class ->
                ctx
                    .fetch(
                        """
                        SELECT c.booking_provider, c.booking_provider_ref
                        FROM campsites c
                        WHERE c.id = ? AND c.deleted_at IS NULL AND c.booking_provider IS NOT NULL
                        """.trimIndent(),
                        campsiteId,
                    ).mapNotNull(::parseCampsiteBookingRef)

            RefValue.CampgroundBookingRef::class ->
                ctx
                    .fetch(
                        """
                        SELECT cg.booking_provider, cg.booking_provider_ref
                        FROM campsites c
                        JOIN campgrounds cg ON cg.id = c.campground_id
                        WHERE c.id = ? AND c.deleted_at IS NULL AND cg.deleted_at IS NULL AND cg.booking_provider IS NOT NULL
                        """.trimIndent(),
                        campsiteId,
                    ).mapNotNull(::parseCampgroundBookingRef)

            RefValue.CampsiteDataRef::class ->
                ctx
                    .fetch(
                        """
                        SELECT c.data_provider, c.data_provider_ref
                        FROM campsites c
                        WHERE c.id = ? AND c.deleted_at IS NULL
                        """.trimIndent(),
                        campsiteId,
                    ).mapNotNull(::parseCampsiteDataRef)

            else -> emptyList()
        }

    private fun resolveFromCampgroundDataRef(
        ref: DataProviderRef,
        to: KClass<*>,
    ): List<RefValue> {
        val provider = ref.provider.id
        val serialized = ref.serialize()
        return when (to) {
            RefValue.CampgroundId::class ->
                ctx
                    .fetch(
                        """
                        SELECT cg.id FROM campgrounds cg
                        WHERE cg.data_provider = ? AND cg.data_provider_ref = ? AND cg.deleted_at IS NULL
                        """.trimIndent(),
                        provider,
                        serialized,
                    ).map { RefValue.CampgroundId(it.get("id", Long::class.java)) }

            else -> emptyList()
        }
    }

    private fun resolveFromCampsiteDataRef(
        ref: DataProviderRef,
        to: KClass<*>,
    ): List<RefValue> {
        val provider = ref.provider.id
        val serialized = ref.serialize()
        return when (to) {
            RefValue.CampsiteId::class ->
                ctx
                    .fetch(
                        """
                        SELECT c.id FROM campsites c
                        WHERE c.data_provider = ? AND c.data_provider_ref = ? AND c.deleted_at IS NULL
                        """.trimIndent(),
                        provider,
                        serialized,
                    ).map { RefValue.CampsiteId(it.get("id", Long::class.java)) }

            else -> emptyList()
        }
    }

    private fun resolveFromCampgroundBookingRef(
        ref: BookingProviderRef,
        to: KClass<*>,
    ): List<RefValue> {
        val provider = ref.provider.id
        val serialized = ref.serialize()
        return when (to) {
            RefValue.CampgroundId::class ->
                ctx
                    .fetch(
                        """
                        SELECT cg.id FROM campgrounds cg
                        WHERE cg.booking_provider = ? AND cg.booking_provider_ref = ? AND cg.deleted_at IS NULL
                        """.trimIndent(),
                        provider,
                        serialized,
                    ).map { RefValue.CampgroundId(it.get("id", Long::class.java)) }

            RefValue.CampsiteId::class ->
                ctx
                    .fetch(
                        """
                        SELECT c.id FROM campsites c
                        JOIN campgrounds cg ON cg.id = c.campground_id
                        WHERE cg.booking_provider = ? AND cg.booking_provider_ref = ? AND c.deleted_at IS NULL AND cg.deleted_at IS NULL
                        """.trimIndent(),
                        provider,
                        serialized,
                    ).map { RefValue.CampsiteId(it.get("id", Long::class.java)) }

            else -> emptyList()
        }
    }

    private fun resolveFromCampsiteBookingRef(
        ref: BookingProviderRef,
        to: KClass<*>,
    ): List<RefValue> {
        val provider = ref.provider.id
        val serialized = ref.serialize()
        return when (to) {
            RefValue.CampsiteId::class ->
                ctx
                    .fetch(
                        """
                        SELECT c.id FROM campsites c
                        WHERE c.booking_provider = ? AND c.booking_provider_ref = ? AND c.deleted_at IS NULL
                        """.trimIndent(),
                        provider,
                        serialized,
                    ).map { RefValue.CampsiteId(it.get("id", Long::class.java)) }

            else -> emptyList()
        }
    }

    private fun parseCampgroundBookingRef(r: org.jooq.Record): RefValue.CampgroundBookingRef? {
        val bp = BookingProvider.fromIdOrNull(r.get("booking_provider", String::class.java)) ?: return null
        val bpRef = r.get("booking_provider_ref", String::class.java) ?: return null
        val parsed = BookingProviderRef.parse(bp, bpRef) ?: return null
        return RefValue.CampgroundBookingRef(parsed)
    }

    private fun parseCampsiteBookingRef(r: org.jooq.Record): RefValue.CampsiteBookingRef? {
        val bp = BookingProvider.fromIdOrNull(r.get("booking_provider", String::class.java)) ?: return null
        val bpRef = r.get("booking_provider_ref", String::class.java) ?: return null
        val parsed = BookingProviderRef.parse(bp, bpRef) ?: return null
        return RefValue.CampsiteBookingRef(parsed)
    }

    private fun parseCampgroundDataRef(r: org.jooq.Record): RefValue.CampgroundDataRef? {
        val dp = DataProvider.fromIdOrNull(r.get("data_provider", String::class.java)) ?: return null
        val dpRef = r.get("data_provider_ref", String::class.java) ?: return null
        val parsed = DataProviderRef.parse(dp, dpRef) ?: return null
        return RefValue.CampgroundDataRef(parsed)
    }

    private fun parseCampsiteDataRef(r: org.jooq.Record): RefValue.CampsiteDataRef? {
        val dp = DataProvider.fromIdOrNull(r.get("data_provider", String::class.java)) ?: return null
        val dpRef = r.get("data_provider_ref", String::class.java) ?: return null
        val parsed = DataProviderRef.parse(dp, dpRef) ?: return null
        return RefValue.CampsiteDataRef(parsed)
    }
}
