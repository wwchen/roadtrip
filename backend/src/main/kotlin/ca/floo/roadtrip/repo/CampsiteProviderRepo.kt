package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.BookingProvider
import ca.floo.roadtrip.model.domain.CampgroundProviderRefRow
import ca.floo.roadtrip.model.domain.CampsiteDateContextRow
import ca.floo.roadtrip.model.domain.CampsiteProviderRefRow
import ca.floo.roadtrip.model.domain.CampsiteVendorRefRow
import org.jooq.DSLContext

class CampsiteProviderRepo(
    private val ctx: DSLContext,
) {
    fun findProviderRef(poiId: Long): CampsiteProviderRefRow? = findProviderRefCandidates(poiId).firstOrNull()

    fun findProviderRefCandidates(poiId: Long): List<CampsiteProviderRefRow> =
        ctx
            .fetch(
                """
                SELECT p.id,
                       cg.booking_provider AS source,
                       cg.booking_provider_ref AS bpref,
                       ST_X(ST_PointOnSurface(p.geom)) AS lng,
                       ST_Y(ST_PointOnSurface(p.geom)) AS lat,
                       cg.source_payload::text AS pref
                FROM pois p
                JOIN poi_campgrounds pc
                  ON pc.poi_id = p.id
                JOIN campgrounds cg
                  ON cg.id = pc.campground_id
                WHERE p.id = ?
                  AND p.deleted_at IS NULL
                  AND p.poi_type = 'campground'
                  AND cg.deleted_at IS NULL
                  AND cg.booking_provider IS NOT NULL
                """.trimIndent(),
                poiId,
            ).mapNotNull(::campgroundProviderRow)

    fun findCampgroundProviderRefCandidates(campgroundId: Long): List<CampgroundProviderRefRow> =
        ctx
            .fetch(
                """
                SELECT cg.id AS campground_id,
                       cg.booking_provider AS source,
                       cg.booking_provider_ref AS bpref,
                       cg.source_payload::text AS pref
                FROM campgrounds cg
                WHERE cg.id = ?
                  AND cg.deleted_at IS NULL
                  AND cg.booking_provider IS NOT NULL
                """.trimIndent(),
                campgroundId,
            ).mapNotNull { r ->
                val pref = r.get("pref") as String? ?: return@mapNotNull null
                CampgroundProviderRefRow(
                    campgroundId = (r.get("campground_id") as Number).toLong(),
                    source = r.get("source") as String,
                    providerRefJson = pref,
                    bookingProviderRef = r.get("bpref") as String?,
                )
            }

    fun findCampsiteProviderRefs(campsiteId: Long): List<CampsiteVendorRefRow> =
        ctx
            .fetch(
                """
                SELECT c.booking_provider AS source,
                       cg.source_payload::text AS pref
                FROM campsites c
                JOIN campgrounds cg ON cg.id = c.campground_id
                WHERE c.id = ?
                  AND c.deleted_at IS NULL
                  AND c.booking_provider IS NOT NULL
                """.trimIndent(),
                campsiteId,
            ).mapNotNull { r ->
                val pref = r.get("pref") as String? ?: return@mapNotNull null
                CampsiteVendorRefRow(
                    source = r.get("source") as String,
                    providerRefJson = pref,
                )
            }

    private fun campgroundProviderRow(r: org.jooq.Record): CampsiteProviderRefRow? {
        val pref = r.get("pref") as String? ?: return null
        val source = r.get("source") as String
        return CampsiteProviderRefRow(
            poiId = (r.get("id") as Number).toLong(),
            source = source,
            providerRefJson = pref,
            bookingProvider = parseBookingProvider(source),
            bookingProviderRef = r.get("bpref") as String?,
            lng = (r.get("lng") as Number?)?.toDouble(),
            lat = (r.get("lat") as Number?)?.toDouble(),
        )
    }

    private fun parseBookingProvider(source: String?): BookingProvider? {
        if (source == null) return null
        return BookingProvider.fromIdOrNull(source)
    }

    fun findDateContext(poiId: Long): CampsiteDateContextRow? {
        val r =
            ctx
                .fetchOne(
                    """
                    SELECT p.id,
                           ST_X(ST_PointOnSurface(geom)) AS lng,
                           ST_Y(ST_PointOnSurface(geom)) AS lat
                    FROM pois p
                    JOIN poi_campgrounds pc
                      ON pc.poi_id = p.id
                    JOIN campgrounds cg
                      ON cg.id = pc.campground_id
                    WHERE p.id = ?
                      AND p.deleted_at IS NULL
                      AND p.poi_type = 'campground'
                      AND cg.deleted_at IS NULL
                    """.trimIndent(),
                    poiId,
                ) ?: return null
        return CampsiteDateContextRow(
            poiId = (r.get("id") as Number).toLong(),
            lng = (r.get("lng") as Number?)?.toDouble(),
            lat = (r.get("lat") as Number?)?.toDouble(),
        )
    }

    fun campgroundExists(poiId: Long): Boolean =
        ctx
            .fetchOne(
                """
                SELECT 1
                FROM pois p
                JOIN poi_campgrounds pc
                  ON pc.poi_id = p.id
                JOIN campgrounds cg
                  ON cg.id = pc.campground_id
                WHERE p.id = ?
                  AND p.deleted_at IS NULL
                  AND p.poi_type = 'campground'
                  AND cg.deleted_at IS NULL
                """.trimIndent(),
                poiId,
            ) != null

    fun findProviderRefs(poiIds: List<Long>): Map<Long, CampsiteProviderRefRow> =
        findProviderRefCandidates(poiIds).mapValues { (_, rows) -> rows.first() }

    fun findProviderRefCandidates(poiIds: List<Long>): Map<Long, List<CampsiteProviderRefRow>> {
        if (poiIds.isEmpty()) return emptyMap()
        val placeholders = poiIds.joinToString(",") { "?" }
        val sql =
            """
            SELECT p.id,
                   cg.booking_provider AS source,
                   cg.booking_provider_ref AS bpref,
                   ST_X(ST_PointOnSurface(p.geom)) AS lng,
                   ST_Y(ST_PointOnSurface(p.geom)) AS lat,
                   cg.source_payload::text AS pref
            FROM pois p
            JOIN poi_campgrounds pc
              ON pc.poi_id = p.id
            JOIN campgrounds cg
              ON cg.id = pc.campground_id
            WHERE p.id IN ($placeholders)
              AND p.deleted_at IS NULL
              AND p.poi_type = 'campground'
              AND cg.deleted_at IS NULL
              AND cg.booking_provider IS NOT NULL
            ORDER BY p.id, cg.id ASC
            """.trimIndent()

        val out = linkedMapOf<Long, MutableList<CampsiteProviderRefRow>>()
        for (r in ctx.fetch(sql, *poiIds.toTypedArray())) {
            val id = (r.get("id") as Number).toLong()
            val row = campgroundProviderRow(r) ?: continue
            out.getOrPut(id) { mutableListOf() } += row
        }
        return out
    }
}
