package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.poi.PoiCentroid
import ca.floo.roadtrip.model.domain.poi.PoiGeometryUpdate
import org.jooq.DSLContext

internal class PoiRepo(
    private val ctx: DSLContext,
) {
    /**
     * The POI's representative interior point, or null when the POI does not
     * exist or is soft-deleted. `ST_PointOnSurface` (not the centroid proper)
     * so a concave or multi-part park geometry still yields a point inside it.
     *
     * One method rather than one per caller: availability target resolution and
     * date-context resolution both need this point, and a soft-deleted POI must
     * not resolve for either.
     */
    fun findCentroid(poiId: Long): PoiCentroid? {
        val record =
            ctx.fetchOne(
                """
                SELECT ST_X(ST_PointOnSurface(p.geom)) AS lng,
                       ST_Y(ST_PointOnSurface(p.geom)) AS lat
                FROM pois p
                WHERE p.id = ? AND p.deleted_at IS NULL
                """.trimIndent(),
                poiId,
            ) ?: return null
        return PoiCentroid(
            lat = (record.get("lat") as? Number)?.toDouble(),
            lng = (record.get("lng") as? Number)?.toDouble(),
        )
    }

    fun insertPoi(
        poiType: String,
        longitude: Double,
        latitude: Double,
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO pois (poi_type, geom)
                VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                RETURNING id
                """.trimIndent(),
                poiType,
                longitude,
                latitude,
            )!!
            .get("id", Long::class.java)

    fun updatePoiGeometry(
        poiId: Long,
        longitude: Double,
        latitude: Double,
    ) {
        ctx.execute(
            """
            UPDATE pois
            SET geom = ST_SetSRID(ST_MakePoint(?, ?), 4326),
                updated_at = now(),
                deleted_at = NULL
            WHERE id = ?
            """.trimIndent(),
            longitude,
            latitude,
            poiId,
        )
    }

    fun bulkUpdatePoiGeometry(rows: List<PoiGeometryUpdate>) {
        if (rows.isEmpty()) return
        val deduped = rows.distinctBy { it.poiId }
        for (chunk in deduped.chunked(BULK_CHUNK_SIZE)) {
            val placeholders = chunk.joinToString(", ") { "(?::bigint, ?::float8, ?::float8)" }
            val sql =
                """
                UPDATE pois AS p
                   SET geom = ST_SetSRID(ST_MakePoint(v.lon, v.lat), 4326),
                       updated_at = now(),
                       deleted_at = NULL
                  FROM (VALUES $placeholders) AS v(id, lon, lat)
                 WHERE p.id = v.id
                """.trimIndent()
            val params = mutableListOf<Any?>()
            for (row in chunk) {
                params += row.poiId
                params += row.longitude
                params += row.latitude
            }
            ctx.execute(sql, *params.toTypedArray())
        }
    }
}
