package ca.floo.roadtrip.repo

import org.jooq.DSLContext
import org.jooq.Record

internal class OnRoutePoiRepo(
    private val ctx: DSLContext,
) {
    fun fetch(
        categories: List<String>,
        corridorLineGeoJson: String,
        corridorRadiusMeters: Double,
        strategy: OnRouteSamplingStrategy = OnRouteSamplingStrategy.None,
    ): List<OnRouteRow> {
        if (categories.isEmpty()) return emptyList()
        val placeholders = categories.joinToString(",") { "?" }

        // Build the corridor LineString once and reuse it for the predicate
        // and the along-route distance projection. ST_LineLocatePoint returns
        // a 0..1 fraction; multiply by ST_Length(::geography) (meters) and
        // divide by 1000 to get km.
        val sql =
            when (strategy) {
                OnRouteSamplingStrategy.None ->
                    """
                    WITH corridor AS (
                      SELECT
                        ST_SetSRID(ST_GeomFromGeoJSON(?), 4326) AS line,
                        ST_CollectionExtract(
                          ST_MakeValid(
                            ST_Buffer(
                              ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)::geography,
                              ?
                            )::geometry
                          ),
                          3
                        ) AS poly,
                        ST_Length(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)::geography) / 1000.0 AS len_km
                    )
                    SELECT id, category, subcategory,
                           ST_X(ST_Centroid(geom)) AS lng,
                           ST_Y(ST_Centroid(geom)) AS lat,
                           ST_LineLocatePoint(corridor.line, ST_Centroid(geom)) * corridor.len_km AS route_km
                    FROM pois, corridor
                    WHERE deleted_at IS NULL
                      AND category IN ($placeholders)
                      AND ST_Intersects(geom, corridor.poly)
                    ORDER BY route_km ASC, id ASC
                    """.trimIndent()
            }

        val args = mutableListOf<Any>()
        args.add(corridorLineGeoJson)
        args.add(corridorLineGeoJson)
        args.add(corridorRadiusMeters)
        args.add(corridorLineGeoJson)
        args.addAll(categories)

        return ctx.fetch(sql, *args.toTypedArray()).map { OnRouteRow.fromRecord(it) }
    }
}

// Slim per-row shape for /api/pois/on-route. Same id + category +
// lat/lng + subcategory as the bbox endpoint, plus along-route distance
// in km so the FE can sort without re-projecting client-side.
internal data class OnRouteRow(
    val id: Long,
    val category: String,
    val subcategory: String?,
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
