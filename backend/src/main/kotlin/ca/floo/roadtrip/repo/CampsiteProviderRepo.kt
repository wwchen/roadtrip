package ca.floo.roadtrip.repo

import org.jooq.DSLContext

data class CampsiteProviderRefRow(
    val poiId: Long,
    val source: String,
    val providerRefJson: String,
    val region: String? = null,
    val country: String? = null,
    val lng: Double? = null,
)

data class CampsiteDateContextRow(
    val poiId: Long,
    val region: String?,
    val country: String?,
    val lng: Double?,
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
                    SELECT id, source, region, country, ST_X(ST_PointOnSurface(geom)) AS lng,
                           provider_ref::text AS pref
                    FROM pois
                    WHERE id = ?
                      AND deleted_at IS NULL
                      AND category = 'campground'
                    """.trimIndent(),
                    poiId,
                ) ?: return null
        val pref = r.get("pref") as String? ?: return null
        return CampsiteProviderRefRow(
            poiId = (r.get("id") as Number).toLong(),
            source = r.get("source") as String,
            providerRefJson = pref,
            region = r.get("region") as String?,
            country = r.get("country") as String?,
            lng = (r.get("lng") as Number?)?.toDouble(),
        )
    }

    fun findDateContext(poiId: Long): CampsiteDateContextRow? {
        val r =
            ctx
                .fetchOne(
                    """
                    SELECT id, region, country, ST_X(ST_PointOnSurface(geom)) AS lng
                    FROM pois
                    WHERE id = ?
                      AND deleted_at IS NULL
                      AND category = 'campground'
                    """.trimIndent(),
                    poiId,
                ) ?: return null
        return CampsiteDateContextRow(
            poiId = (r.get("id") as Number).toLong(),
            region = r.get("region") as String?,
            country = r.get("country") as String?,
            lng = (r.get("lng") as Number?)?.toDouble(),
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
                FROM pois
                WHERE id = ?
                  AND deleted_at IS NULL
                  AND category = 'campground'
                """.trimIndent(),
                poiId,
            ) != null

    /** Same as [findProviderRef] but for a batch — one DB round-trip. */
    fun findProviderRefs(poiIds: List<Long>): Map<Long, CampsiteProviderRefRow> {
        if (poiIds.isEmpty()) return emptyMap()
        val placeholders = poiIds.joinToString(",") { "?" }
        val sql =
            """
            SELECT id, source, region, country, ST_X(ST_PointOnSurface(geom)) AS lng,
                   provider_ref::text AS pref
            FROM pois
            WHERE id IN ($placeholders)
              AND deleted_at IS NULL
              AND category = 'campground'
              AND provider_ref IS NOT NULL
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
                    region = r.get("region") as String?,
                    country = r.get("country") as String?,
                    lng = (r.get("lng") as Number?)?.toDouble(),
                )
        }
        return out
    }
}
