package ca.floo.roadtrip.repo

import org.jooq.DSLContext
import org.jooq.Record

internal class OnRoutePoiRepo(
    private val ctx: DSLContext,
) {
    fun fetch(
        categories: List<String>,
        corridorLineGeoJson: String,
        corridorPolygonGeoJson: String,
        strategy: OnRouteSamplingStrategy = OnRouteSamplingStrategy.None,
    ): List<OnRouteRow> {
        if (categories.isEmpty()) return emptyList()
        val placeholders = categories.joinToString(",") { "?" }

        // Use the route API's corridor polygon for the spatial predicate
        // and the route LineString for along-route distance projection.
        // ST_LineLocatePoint returns a 0..1 fraction; multiply by
        // ST_Length(::geography) (meters) and divide by 1000 to get km.
        val sql =
            when (strategy) {
                OnRouteSamplingStrategy.None ->
                    """
                    WITH corridor AS (
                      SELECT
                        ST_SetSRID(ST_GeomFromGeoJSON(?), 4326) AS line,
                        ST_SetSRID(ST_GeomFromGeoJSON(?), 4326) AS poly,
                        ST_Length(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)::geography) / 1000.0 AS len_km
                    ),
                    candidates AS (
                      SELECT
                        id,
                        category,
                        subcategory,
                        agency,
                        ST_X(ST_Centroid(geom)) AS lng,
                        ST_Y(ST_Centroid(geom)) AS lat,
                        ST_LineLocatePoint(corridor.line, ST_Centroid(geom)) * corridor.len_km AS route_km,
                        CASE
                          WHEN category = 'campground' AND NULLIF(provider_ref ->> 'recgov_id', '') IS NOT NULL
                            THEN 'recgov:' || (provider_ref ->> 'recgov_id')
                          WHEN category = 'campground'
                            AND NULLIF(provider_ref ->> 'transactionLocationId', '') IS NOT NULL
                            AND NULLIF(provider_ref ->> 'mapId', '') IS NOT NULL
                            THEN 'aspira:' || source || ':' ||
                              (provider_ref ->> 'transactionLocationId') || ':' ||
                              (provider_ref ->> 'mapId')
                          WHEN category = 'campground' AND NULLIF(provider_ref ->> 'park_id', '') IS NOT NULL
                            THEN 'reserveamerica:' || source || ':' || (provider_ref ->> 'park_id')
                          WHEN category = 'campground' AND NULLIF(provider_ref ->> 'facility_id', '') IS NOT NULL
                            THEN 'reserveamerica:' || source || ':' || (provider_ref ->> 'facility_id')
                          ELSE source || ':' || source_id
                        END AS poi_key
                      FROM (
                        SELECT
                          p.id,
                          p.geom,
                          p.poi_type AS category,
                          cg.kind AS subcategory,
                          cg.management->>'agency' AS agency,
                          COALESCE(cg.etl_source, gvr.vendor, p.poi_type) AS source,
                          COALESCE(gvr.external_id, ts.location_slug, pf.location_id, p.id::text) AS source_id,
                          COALESCE(gvr.payload, '{}'::jsonb) AS provider_ref
                        FROM pois p
                        LEFT JOIN poi_campgrounds pc ON pc.poi_id = p.id
                        LEFT JOIN campgrounds cg ON cg.id = pc.campground_id AND cg.deleted_at IS NULL
                        LEFT JOIN campground_vendor_refs cgvr ON cgvr.campground_id = cg.id AND cgvr.is_primary
                        LEFT JOIN vendor_refs gvr ON gvr.id = cgvr.vendor_ref_id AND gvr.deleted_at IS NULL
                        LEFT JOIN poi_tesla_superchargers pts ON pts.poi_id = p.id
                        LEFT JOIN tesla_superchargers ts ON ts.id = pts.tesla_supercharger_id AND ts.deleted_at IS NULL
                        LEFT JOIN poi_planet_fitness_locations ppf ON ppf.poi_id = p.id
                        LEFT JOIN planet_fitness_locations pf ON pf.id = ppf.planet_fitness_location_id AND pf.deleted_at IS NULL
                        WHERE p.deleted_at IS NULL
                      ) catalog, corridor
                      WHERE TRUE
                        AND category IN ($placeholders)
                        AND ST_Within(ST_Centroid(geom), corridor.poly)
                    ),
                    ranked AS (
                      SELECT *,
                             ROW_NUMBER() OVER (PARTITION BY poi_key ORDER BY route_km ASC, id ASC) AS rn
                      FROM candidates
                    )
                    SELECT id, category, subcategory, agency, lng, lat, route_km
                    FROM ranked
                    WHERE rn = 1
                    ORDER BY route_km ASC, id ASC
                    """.trimIndent()
            }

        val args = mutableListOf<Any>()
        args.add(corridorLineGeoJson)
        args.add(corridorPolygonGeoJson)
        args.add(corridorLineGeoJson)
        args.addAll(categories)

        return ctx.fetch(sql, *args.toTypedArray()).map { OnRouteRow.fromRecord(it) }
    }
}

// Slim per-row shape for /api/pois/on-route. Same id + category +
// lat/lng + subcategory + agency as the bbox endpoint, plus along-route
// distance in km so the FE can sort without re-projecting client-side.
internal data class OnRouteRow(
    val id: Long,
    val category: String,
    val subcategory: String?,
    val agency: String?,
    val lng: Double,
    val lat: Double,
    val routeKm: Double,
) {
    companion object {
        fun fromRecord(record: Record): OnRouteRow =
            OnRouteRow(
                id = (record.get("id") as Number).toLong(),
                category = record.get("category") as String,
                subcategory = record.get("subcategory") as String?,
                agency = record.get("agency") as String?,
                lng = (record.get("lng") as Number).toDouble(),
                lat = (record.get("lat") as Number).toDouble(),
                routeKm = (record.get("route_km") as Number).toDouble(),
            )
    }
}

// Sampling strategy slot for the on-route endpoint. Today we always
// return everything inside the corridor; future variants (even-along-
// route, score-weighted, time-bucketed) plug in here without touching
// the route handler.
internal sealed interface OnRouteSamplingStrategy {
    data object None : OnRouteSamplingStrategy
}
