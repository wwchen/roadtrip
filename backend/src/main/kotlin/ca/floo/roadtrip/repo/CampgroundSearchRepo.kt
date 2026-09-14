package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.CampgroundSearchFilter
import ca.floo.roadtrip.model.domain.CampgroundSearchResult
import ca.floo.roadtrip.model.domain.InvalidBoundaryException
import ca.floo.roadtrip.support.TOPOLOGY_FAULT_EMPTY_RESULT
import ca.floo.roadtrip.support.causeChain
import ca.floo.roadtrip.support.isGeometryParseFault
import ca.floo.roadtrip.support.isTopologyFault
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.slf4j.LoggerFactory

private val campgroundSearchLog = LoggerFactory.getLogger("CampgroundSearchRepo")

/**
 * Campground POIs inside a boundary that pass a filter. One row per campground
 * thanks to `campground_site_summary`; a campground with no summary row has no
 * live sites and passes the site filters as "no data".
 */
internal class CampgroundSearchRepo(
    private val ctx: DSLContext,
    private val enabledDataProviders: Set<String>,
) {
    fun searchWithinBoundary(
        boundaryGeoJson: String,
        filter: CampgroundSearchFilter,
        limit: Int,
    ): CampgroundSearchResult {
        if (enabledDataProviders.isEmpty()) {
            return CampgroundSearchResult(emptyList(), totalInBoundary = 0, totalMatching = 0, truncated = false)
        }

        val predicates = mutableListOf<String>()
        val predicateArgs = mutableListOf<Any>()
        filter.siteType?.let {
            predicates += "(s.site_total IS NULL OR COALESCE((s.site_counts->>?)::int, 0) > 0)"
            predicateArgs += it
        }
        filter.groupSize?.let {
            predicates += "(s.max_people IS NULL OR s.max_people >= ?)"
            predicateArgs += it
        }
        for (key in filter.amenities) {
            predicates +=
                """NOT EXISTS (
                     SELECT 1 FROM jsonb_array_elements(cg.amenities) a
                     WHERE a->>'key' = ? AND COALESCE((a->>'present')::boolean, true) = false
                   )"""
            predicateArgs += key
        }
        val passes = if (predicates.isEmpty()) "true" else predicates.joinToString(" AND ")
        val providerPlaceholders = enabledDataProviders.joinToString(",") { "?" }

        val sql =
            """
            WITH boundary AS (
              SELECT ST_SetSRID(ST_GeomFromGeoJSON(?), 4326) AS poly
            ),
            inside AS (
              SELECT p.id AS poi_id,
                     ST_Distance(
                       ST_Centroid(p.geom)::geography,
                       ST_Centroid(boundary.poly)::geography
                     ) AS dist,
                     ($passes) AS passes
              FROM pois p
              JOIN poi_campgrounds pc ON pc.poi_id = p.id
              JOIN campgrounds cg ON cg.id = pc.campground_id AND cg.deleted_at IS NULL
              LEFT JOIN campground_site_summary s ON s.campground_id = cg.id,
              boundary
              WHERE p.deleted_at IS NULL
                AND p.poi_type = 'campground'
                AND cg.data_provider IN ($providerPlaceholders)
                AND p.geom && boundary.poly
                AND ST_Within(ST_Centroid(p.geom), boundary.poly)
            )
            SELECT poi_id,
                   passes,
                   COUNT(*) OVER () AS total_in_boundary,
                   COUNT(*) FILTER (WHERE passes) OVER () AS total_passing
            FROM inside
            ORDER BY passes DESC, dist ASC, poi_id ASC
            LIMIT ?
            """.trimIndent()

        val args = mutableListOf<Any>(boundaryGeoJson)
        args.addAll(predicateArgs)
        args.addAll(enabledDataProviders)
        args += limit

        val rows =
            try {
                ctx.fetch(sql, *args.toTypedArray())
            } catch (e: DataAccessException) {
                if (isGeometryParseFault(e)) throw InvalidBoundaryException("boundary is not valid GeoJSON geometry", e)
                if (!isTopologyFault(e)) throw e
                campgroundSearchLog.warn(TOPOLOGY_FAULT_EMPTY_RESULT, causeChain(e))
                return CampgroundSearchResult(emptyList(), totalInBoundary = 0, totalMatching = 0, truncated = false)
            }
        val passing = rows.filter { it.get("passes", Boolean::class.java) }
        val totalInBoundary = rows.firstOrNull()?.let { (it.get("total_in_boundary") as Number).toInt() } ?: 0
        val totalPassing = rows.firstOrNull()?.let { (it.get("total_passing") as Number).toInt() } ?: 0
        return CampgroundSearchResult(
            poiIds = passing.map { (it.get("poi_id") as Number).toLong() },
            totalInBoundary = totalInBoundary,
            totalMatching = totalPassing,
            truncated = totalPassing > passing.size,
        )
    }
}
