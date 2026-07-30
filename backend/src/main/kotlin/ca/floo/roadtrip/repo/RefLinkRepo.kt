package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import org.jooq.DSLContext
import org.jooq.Record

/**
 * The link queries behind ref resolution: POI ↔ campground ↔ campsite ids, and
 * the typed provider refs attached to those rows.
 *
 * A cross-entity read repo (like `PoiServingRepo`), named for its use case: no
 * single entity owns "what is reachable from this ref", and the resolver that
 * consumes it is policy, not persistence. Every query filters soft-deleted rows
 * on every table it touches — a deleted row must not be reachable from a live
 * one in either direction.
 */
class RefLinkRepo(
    private val ctx: DSLContext,
) {
    fun campgroundIdsForPoi(poiId: Long): List<Long> =
        ctx
            .fetch(
                """
                SELECT pc.campground_id
                FROM poi_campgrounds pc
                JOIN campgrounds cg ON cg.id = pc.campground_id
                WHERE pc.poi_id = ? AND cg.deleted_at IS NULL
                """.trimIndent(),
                poiId,
            ).map { it.get("campground_id", Long::class.java) }

    fun campsiteIdsForPoi(poiId: Long): List<Long> =
        ctx
            .fetch(
                """
                SELECT c.id
                FROM campsites c
                JOIN poi_campgrounds pc ON pc.campground_id = c.campground_id
                WHERE pc.poi_id = ? AND c.deleted_at IS NULL
                """.trimIndent(),
                poiId,
            ).map { it.get("id", Long::class.java) }

    fun campgroundBookingRefsForPoi(poiId: Long): List<BookingProviderRef> =
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
            ).mapNotNull(::bookingRef)

    fun campsiteBookingRefsForPoi(poiId: Long): List<BookingProviderRef> =
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
            ).mapNotNull(::bookingRef)

    fun poiIdsForCampground(campgroundId: Long): List<Long> =
        ctx
            .fetch(
                """
                SELECT pc.poi_id
                FROM poi_campgrounds pc
                JOIN pois p ON p.id = pc.poi_id
                WHERE pc.campground_id = ? AND p.deleted_at IS NULL
                """.trimIndent(),
                campgroundId,
            ).map { it.get("poi_id", Long::class.java) }

    fun campsiteIdsForCampground(campgroundId: Long): List<Long> =
        ctx
            .fetch(
                """
                SELECT c.id FROM campsites c
                WHERE c.campground_id = ? AND c.deleted_at IS NULL
                """.trimIndent(),
                campgroundId,
            ).map { it.get("id", Long::class.java) }

    fun bookingRefsForCampground(campgroundId: Long): List<BookingProviderRef> =
        ctx
            .fetch(
                """
                SELECT cg.booking_provider, cg.booking_provider_ref
                FROM campgrounds cg
                WHERE cg.id = ? AND cg.deleted_at IS NULL AND cg.booking_provider IS NOT NULL
                """.trimIndent(),
                campgroundId,
            ).mapNotNull(::bookingRef)

    fun dataRefsForCampground(campgroundId: Long): List<DataProviderRef> =
        ctx
            .fetch(
                """
                SELECT cg.data_provider, cg.data_provider_ref
                FROM campgrounds cg
                WHERE cg.id = ? AND cg.deleted_at IS NULL
                """.trimIndent(),
                campgroundId,
            ).mapNotNull(::dataRef)

    fun poiIdsForCampsite(campsiteId: Long): List<Long> =
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
            ).map { it.get("poi_id", Long::class.java) }

    fun campgroundIdsForCampsite(campsiteId: Long): List<Long> =
        ctx
            .fetch(
                """
                SELECT c.campground_id FROM campsites c
                WHERE c.id = ? AND c.deleted_at IS NULL
                """.trimIndent(),
                campsiteId,
            ).map { it.get("campground_id", Long::class.java) }

    fun bookingRefsForCampsite(campsiteId: Long): List<BookingProviderRef> =
        ctx
            .fetch(
                """
                SELECT c.booking_provider, c.booking_provider_ref
                FROM campsites c
                WHERE c.id = ? AND c.deleted_at IS NULL AND c.booking_provider IS NOT NULL
                """.trimIndent(),
                campsiteId,
            ).mapNotNull(::bookingRef)

    fun parentCampgroundBookingRefsForCampsite(campsiteId: Long): List<BookingProviderRef> =
        ctx
            .fetch(
                """
                SELECT cg.booking_provider, cg.booking_provider_ref
                FROM campsites c
                JOIN campgrounds cg ON cg.id = c.campground_id
                WHERE c.id = ? AND c.deleted_at IS NULL AND cg.deleted_at IS NULL AND cg.booking_provider IS NOT NULL
                """.trimIndent(),
                campsiteId,
            ).mapNotNull(::bookingRef)

    fun dataRefsForCampsite(campsiteId: Long): List<DataProviderRef> =
        ctx
            .fetch(
                """
                SELECT c.data_provider, c.data_provider_ref
                FROM campsites c
                WHERE c.id = ? AND c.deleted_at IS NULL
                """.trimIndent(),
                campsiteId,
            ).mapNotNull(::dataRef)

    fun campgroundIdsByDataRef(ref: DataProviderRef): List<Long> =
        ctx
            .fetch(
                """
                SELECT cg.id FROM campgrounds cg
                WHERE cg.data_provider = ? AND cg.data_provider_ref = ? AND cg.deleted_at IS NULL
                """.trimIndent(),
                ref.provider.id,
                ref.serialize(),
            ).map { it.get("id", Long::class.java) }

    fun campsiteIdsByDataRef(ref: DataProviderRef): List<Long> =
        ctx
            .fetch(
                """
                SELECT c.id FROM campsites c
                WHERE c.data_provider = ? AND c.data_provider_ref = ? AND c.deleted_at IS NULL
                """.trimIndent(),
                ref.provider.id,
                ref.serialize(),
            ).map { it.get("id", Long::class.java) }

    fun campgroundIdsByBookingRef(ref: BookingProviderRef): List<Long> =
        ctx
            .fetch(
                """
                SELECT cg.id FROM campgrounds cg
                WHERE cg.booking_provider = ? AND cg.booking_provider_ref = ? AND cg.deleted_at IS NULL
                """.trimIndent(),
                ref.provider.id,
                ref.serialize(),
            ).map { it.get("id", Long::class.java) }

    fun campsiteIdsByCampgroundBookingRef(ref: BookingProviderRef): List<Long> =
        ctx
            .fetch(
                """
                SELECT c.id FROM campsites c
                JOIN campgrounds cg ON cg.id = c.campground_id
                WHERE cg.booking_provider = ? AND cg.booking_provider_ref = ? AND c.deleted_at IS NULL AND cg.deleted_at IS NULL
                """.trimIndent(),
                ref.provider.id,
                ref.serialize(),
            ).map { it.get("id", Long::class.java) }

    fun campsiteIdsByBookingRef(ref: BookingProviderRef): List<Long> =
        ctx
            .fetch(
                """
                SELECT c.id FROM campsites c
                WHERE c.booking_provider = ? AND c.booking_provider_ref = ? AND c.deleted_at IS NULL
                """.trimIndent(),
                ref.provider.id,
                ref.serialize(),
            ).map { it.get("id", Long::class.java) }

    /** Unparseable stored refs read as "no ref": a bad row must not fail the
     *  whole resolution, and there is nothing the caller could do with it. */
    private fun bookingRef(record: Record): BookingProviderRef? {
        val provider = BookingProvider.fromIdOrNull(record.get("booking_provider", String::class.java)) ?: return null
        val ref = record.get("booking_provider_ref", String::class.java) ?: return null
        return BookingProviderRef.parse(provider, ref)
    }

    private fun dataRef(record: Record): DataProviderRef? {
        val provider = DataProvider.fromIdOrNull(record.get("data_provider", String::class.java)) ?: return null
        val ref = record.get("data_provider_ref", String::class.java) ?: return null
        return DataProviderRef.parse(provider, ref)
    }
}
