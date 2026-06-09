package ca.floo.roadtrip.repo

import org.jooq.DSLContext

internal class RouteCorridorRepo(
    private val ctx: DSLContext,
) {
    fun bufferedPolygonGeoJson(
        lineGeoJson: String,
        radiusMeters: Double,
    ): String {
        val record =
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
        return record?.get("geom_json") as? String
            ?: error("route corridor query returned no geometry")
    }
}
