package ca.floo.roadtrip.repo

import org.jooq.DSLContext

data class CampsiteProviderRefRow(
    val poiId: Long,
    val source: String,
    val providerRefJson: String,
    val lng: Double? = null,
    val lat: Double? = null,
)

data class CampsiteVendorRefRow(
    val source: String,
    val providerRefJson: String,
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
    fun findProviderRef(poiId: Long): CampsiteProviderRefRow? = findProviderRefCandidates(poiId).firstOrNull()

    /**
     * Provider refs for a single campground POI's entire match group.
     *
     * Enumerates every campground vendor ref across every row that shares the
     * POI's canonical match group (via `campground_canonical.member_ids`), not
     * just the campground currently linked through `poi_campgrounds`. Ordering:
     * preferred availability source first, provider-shaped payload next, then
     * the canonical winner ahead of its siblings, member id, and vendor ref id
     * as final tiebreakers.
     */
    fun findProviderRefCandidates(poiId: Long): List<CampsiteProviderRefRow> =
        ctx
            .fetch(
                """
                SELECT p.id,
                       vr.vendor AS source,
                       ST_X(ST_PointOnSurface(geom)) AS lng,
                       ST_Y(ST_PointOnSurface(geom)) AS lat,
                       vr.payload::text AS pref
                FROM pois p
                JOIN poi_campgrounds pc
                  ON pc.poi_id = p.id
                JOIN campground_canonical cc
                  ON cc.id = pc.campground_id
                JOIN LATERAL unnest(cc.member_ids) AS members(member_id) ON TRUE
                JOIN campground_vendor_refs cvr
                  ON cvr.campground_id = members.member_id
                JOIN vendor_refs vr
                  ON vr.id = cvr.vendor_ref_id
                WHERE p.id = ?
                  AND p.deleted_at IS NULL
                  AND vr.entity_type = 'campground'
                  AND vr.deleted_at IS NULL
                ORDER BY
                  CASE WHEN vr.vendor = cc.preferred_availability_source THEN 1 ELSE 0 END DESC,
                  CASE WHEN ${providerRefShapeSql("vr.payload")} THEN 1 ELSE 0 END DESC,
                  CASE WHEN members.member_id = cc.id THEN 0 ELSE 1 END ASC,
                  members.member_id ASC,
                  cvr.vendor_ref_id ASC
                """.trimIndent(),
                poiId,
            ).mapNotNull(::campgroundProviderRow)

    fun findCampsiteProviderRefs(campsiteId: Long): List<CampsiteVendorRefRow> =
        ctx
            .fetch(
                """
                SELECT vr.vendor AS source,
                       vr.payload::text AS pref
                FROM campsite_vendor_refs cvr
                JOIN vendor_refs vr
                  ON vr.id = cvr.vendor_ref_id
                WHERE cvr.campsite_id = ?
                  AND vr.entity_type = 'campsite'
                  AND vr.deleted_at IS NULL
                ORDER BY
                  CASE WHEN ${providerRefShapeSql("vr.payload")} THEN 1 ELSE 0 END DESC,
                  cvr.vendor_ref_id ASC
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
    fun findProviderRefs(poiIds: List<Long>): Map<Long, CampsiteProviderRefRow> =
        findProviderRefCandidates(poiIds).mapValues { (_, rows) -> rows.first() }

    /** Same as [findProviderRefCandidates] but for a batch — one DB round-trip. */
    fun findProviderRefCandidates(poiIds: List<Long>): Map<Long, List<CampsiteProviderRefRow>> {
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
            JOIN campground_canonical cc
              ON cc.id = pc.campground_id
            JOIN LATERAL unnest(cc.member_ids) AS members(member_id) ON TRUE
            JOIN campground_vendor_refs cvr
              ON cvr.campground_id = members.member_id
            JOIN vendor_refs vr
              ON vr.id = cvr.vendor_ref_id
            WHERE p.id IN ($placeholders)
              AND p.deleted_at IS NULL
              AND vr.entity_type = 'campground'
              AND vr.deleted_at IS NULL
            ORDER BY
              p.id,
              CASE WHEN vr.vendor = cc.preferred_availability_source THEN 1 ELSE 0 END DESC,
              CASE WHEN ${providerRefShapeSql("vr.payload")} THEN 1 ELSE 0 END DESC,
              CASE WHEN members.member_id = cc.id THEN 0 ELSE 1 END ASC,
              members.member_id ASC,
              cvr.vendor_ref_id ASC
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
