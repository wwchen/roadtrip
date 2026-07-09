package ca.floo.roadtrip.repo

import org.jooq.DSLContext

data class CampsiteProviderRefRow(
    val poiId: Long,
    val source: String,
    val providerRefJson: String,
    val lng: Double? = null,
    val lat: Double? = null,
)

data class CampsiteDateContextRow(
    val poiId: Long,
    val lng: Double?,
    val lat: Double?,
)

class CampsiteProviderRepo(
    private val ctx: DSLContext,
) {
    /** Provider ref for a single campground POI, or null when not found / unsupported. */
    fun findProviderRef(poiId: Long): CampsiteProviderRefRow? {
        val r =
            ctx
                .fetchOne(
                    """
                    SELECT p.id,
                           vr.vendor AS source,
                           ST_X(ST_PointOnSurface(geom)) AS lng,
                           ST_Y(ST_PointOnSurface(geom)) AS lat,
                           vr.payload::text AS pref
                    FROM pois p
                    JOIN poi_campgrounds pc
                      ON pc.poi_id = p.id
                    JOIN campgrounds cg
                      ON cg.id = pc.campground_id
                    JOIN LATERAL (
                      SELECT ref.vendor, ref.payload
                      FROM campground_vendor_refs cvr
                      JOIN vendor_refs ref
                        ON ref.id = cvr.vendor_ref_id
                      WHERE cvr.campground_id = cg.id
                        AND ref.entity_type = 'campground'
                        AND ref.deleted_at IS NULL
                      ORDER BY
                        CASE WHEN ${providerRefShapeSql("ref.payload")} THEN 1 ELSE 0 END DESC,
                        cvr.is_primary DESC,
                        cvr.vendor_ref_id ASC
                      LIMIT 1
                    ) vr ON true
                    WHERE p.id = ?
                      AND p.deleted_at IS NULL
                      AND p.poi_type = 'campground'
                      AND cg.deleted_at IS NULL
                    """.trimIndent(),
                    poiId,
                ) ?: return null
        val pref = r.get("pref") as String? ?: return null
        return CampsiteProviderRefRow(
            poiId = (r.get("id") as Number).toLong(),
            source = r.get("source") as String,
            providerRefJson = pref,
            lng = (r.get("lng") as Number?)?.toDouble(),
            lat = (r.get("lat") as Number?)?.toDouble(),
        )
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

    /**
     * Existence-only check for an active campground POI. Use when you need
     * to distinguish "POI doesn't exist" (404) from "POI exists but has no
     * provider_ref" (no online reservations) — [findProviderRef] returns null
     * in both cases.
     */
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

    /** Same as [findProviderRef] but for a batch — one DB round-trip. */
    fun findProviderRefs(poiIds: List<Long>): Map<Long, CampsiteProviderRefRow> {
        if (poiIds.isEmpty()) return emptyMap()
        val placeholders = poiIds.joinToString(",") { "?" }
        val sql =
            """
            SELECT p.id,
                   vr.vendor AS source,
                   ST_X(ST_PointOnSurface(geom)) AS lng,
                   ST_Y(ST_PointOnSurface(geom)) AS lat,
                   vr.payload::text AS pref
            FROM pois p
            JOIN poi_campgrounds pc
              ON pc.poi_id = p.id
            JOIN campgrounds cg
              ON cg.id = pc.campground_id
            JOIN LATERAL (
              SELECT ref.vendor, ref.payload
              FROM campground_vendor_refs cvr
              JOIN vendor_refs ref
                ON ref.id = cvr.vendor_ref_id
              WHERE cvr.campground_id = cg.id
                AND ref.entity_type = 'campground'
                AND ref.deleted_at IS NULL
              ORDER BY
                CASE WHEN ${providerRefShapeSql("ref.payload")} THEN 1 ELSE 0 END DESC,
                cvr.is_primary DESC,
                cvr.vendor_ref_id ASC
              LIMIT 1
            ) vr ON true
            WHERE p.id IN ($placeholders)
              AND p.deleted_at IS NULL
              AND p.poi_type = 'campground'
              AND cg.deleted_at IS NULL
            """.trimIndent()

        val out = mutableMapOf<Long, CampsiteProviderRefRow>()
        for (r in ctx.fetch(sql, *poiIds.toTypedArray())) {
            val id = (r.get("id") as Number).toLong()
            val pref = r.get("pref") as String? ?: continue
            out[id] =
                CampsiteProviderRefRow(
                    poiId = id,
                    source = r.get("source") as String,
                    providerRefJson = pref,
                    lng = (r.get("lng") as Number?)?.toDouble(),
                    lat = (r.get("lat") as Number?)?.toDouble(),
                )
        }
        return out
    }
}
