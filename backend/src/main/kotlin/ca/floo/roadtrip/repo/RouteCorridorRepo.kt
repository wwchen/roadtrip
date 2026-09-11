package ca.floo.roadtrip.repo

import ca.floo.roadtrip.support.CorridorUnavailableException
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException

internal class RouteCorridorRepo(
    private val ctx: DSLContext,
) {
    /** @throws CorridorUnavailableException when PostGIS cannot buffer the line. */
    fun bufferedPolygonGeoJson(
        lineGeoJson: String,
        radiusMeters: Double,
    ): String {
        val record =
            try {
                ctx.fetchOne(
                    """
                    SELECT ST_AsGeoJSON(
                             ST_CollectionExtract(
                               ST_MakeValid(
                                 ST_Buffer(
                                   ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)::geography,
                                   ?
                                 )::geometry
                               ),
                               3
                             )
                           ) AS geom_json
                    """.trimIndent(),
                    lineGeoJson,
                    radiusMeters,
                )
            } catch (e: DataAccessException) {
                throw CorridorUnavailableException(e)
            }
        return record?.get("geom_json") as? String
            ?: error("route corridor query returned no geometry")
    }
}
