package ca.floo.roadtrip.repo

import org.jooq.DSLContext

data class CampsiteProviderRefRow(
    val poiId: Long,
    val source: String,
    val providerRefJson: String,
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
                    SELECT id, source, provider_ref::text AS pref
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
        )
    }

    /** Same as [findProviderRef] but for a batch — one DB round-trip. */
    fun findProviderRefs(poiIds: List<Long>): Map<Long, CampsiteProviderRefRow> {
        if (poiIds.isEmpty()) return emptyMap()
        val placeholders = poiIds.joinToString(",") { "?" }
        val sql =
            """
            SELECT id, source, provider_ref::text AS pref
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
                )
        }
        return out
    }
}
