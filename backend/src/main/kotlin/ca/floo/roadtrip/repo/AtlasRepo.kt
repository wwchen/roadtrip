package ca.floo.roadtrip.repo

import org.jooq.DSLContext

internal data class AtlasRegionAgencyCount(
    val region: String?,
    val agency: String?,
    val campgroundCount: Int,
)

internal data class AtlasAgencyName(
    val agency: String?,
    val name: String,
)

internal data class AtlasCampgroundRow(
    val id: Long,
    val poiId: Long?,
    val name: String,
    val siteCount: Int,
)

internal data class AtlasCampsiteRow(
    val id: Long,
    val name: String,
    val loopName: String?,
)

/**
 * Read-model for the Atlas index. Every tier derives from existing columns — the
 * state/province from `location->>'region'` (with `address.state_code` as a
 * fallback), the managing agency from `management->>'agency'`, and the
 * campground -> campsite edge from the `campsites.campground_id` FK. Raw SQL
 * (like [PoiServingRepo]) because the region lives inside JSONB.
 */
internal class AtlasRepo(
    private val ctx: DSLContext,
) {
    // Uppercased so case variants of a code collapse to one region bucket.
    private val regionExpr = "UPPER(COALESCE(cg.location->>'region', cg.location->'address'->>'state_code'))"

    fun regionAgencyCounts(): List<AtlasRegionAgencyCount> =
        ctx
            .fetch(
                """
                SELECT $regionExpr AS region,
                       cg.management->>'agency' AS agency,
                       COUNT(*) AS n
                FROM campgrounds cg
                WHERE cg.deleted_at IS NULL
                GROUP BY 1, 2
                """.trimIndent(),
            ).map { r ->
                AtlasRegionAgencyCount(
                    region = r.get("region") as String?,
                    agency = r.get("agency") as String?,
                    campgroundCount = (r.get("n") as Number).toInt(),
                )
            }

    /** Up to [perAgency] campground names per agency in a region, for teasers. */
    fun agencyNameSamples(
        region: String?,
        perAgency: Int,
    ): List<AtlasAgencyName> {
        val (regionClause, regionArgs) = regionFilter(region)
        val args = regionArgs + perAgency
        return ctx
            .fetch(
                """
                SELECT agency, name FROM (
                  SELECT cg.management->>'agency' AS agency,
                         COALESCE(cg.name, '(unnamed)') AS name,
                         ROW_NUMBER() OVER (
                           PARTITION BY cg.management->>'agency' ORDER BY cg.name
                         ) AS rn
                  FROM campgrounds cg
                  WHERE cg.deleted_at IS NULL AND $regionClause
                ) s
                WHERE rn <= ?
                """.trimIndent(),
                *args.toTypedArray(),
            ).map { r -> AtlasAgencyName(r.get("agency") as String?, r.get("name") as String) }
    }

    fun campgrounds(
        region: String?,
        agencies: List<String>,
        includeNullAgency: Boolean,
        limit: Int,
    ): List<AtlasCampgroundRow> {
        val (regionClause, regionArgs) = regionFilter(region)
        val args = mutableListOf<Any>()
        args.addAll(regionArgs)
        val agencyConds = mutableListOf<String>()
        if (agencies.isNotEmpty()) {
            agencyConds.add("cg.management->>'agency' IN (${agencies.joinToString(",") { "?" }})")
            args.addAll(agencies)
        }
        if (includeNullAgency) agencyConds.add("cg.management->>'agency' IS NULL")
        if (agencyConds.isEmpty()) return emptyList()
        val agencyClause = agencyConds.joinToString(" OR ", prefix = "(", postfix = ")")
        args.add(limit)
        return ctx
            .fetch(
                """
                SELECT cg.id AS id,
                       pc.poi_id AS poi_id,
                       COALESCE(cg.name, '(unnamed)') AS name,
                       (SELECT COUNT(*) FROM campsites cs WHERE cs.campground_id = cg.id) AS site_count
                FROM campgrounds cg
                LEFT JOIN poi_campgrounds pc ON pc.campground_id = cg.id
                WHERE cg.deleted_at IS NULL AND $regionClause AND $agencyClause
                ORDER BY name
                LIMIT ?
                """.trimIndent(),
                *args.toTypedArray(),
            ).map { r ->
                AtlasCampgroundRow(
                    id = (r.get("id") as Number).toLong(),
                    poiId = (r.get("poi_id") as Number?)?.toLong(),
                    name = r.get("name") as String,
                    siteCount = (r.get("site_count") as Number).toInt(),
                )
            }
    }

    fun campsites(
        campgroundId: Long,
        limit: Int,
    ): List<AtlasCampsiteRow> =
        ctx
            .fetch(
                """
                SELECT id, COALESCE(name, '(unnamed)') AS name, loop_name
                FROM campsites
                WHERE campground_id = ?
                ORDER BY loop_name NULLS FIRST, name
                LIMIT ?
                """.trimIndent(),
                campgroundId,
                limit,
            ).map { r ->
                AtlasCampsiteRow(
                    id = (r.get("id") as Number).toLong(),
                    name = r.get("name") as String,
                    loopName = r.get("loop_name") as String?,
                )
            }

    private fun regionFilter(region: String?): Pair<String, List<Any>> =
        if (region == null) {
            "$regionExpr IS NULL" to emptyList()
        } else {
            "$regionExpr = ?" to listOf(region)
        }
}
