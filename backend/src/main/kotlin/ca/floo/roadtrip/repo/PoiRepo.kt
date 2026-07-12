package ca.floo.roadtrip.repo

import org.jooq.DSLContext

internal class PoiRepo(
    private val ctx: DSLContext,
) {
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

    fun softDeletePoiIfActive(poiId: Long) {
        ctx.execute(
            """
            UPDATE pois
            SET deleted_at = now(),
                updated_at = now()
            WHERE id = ?
              AND deleted_at IS NULL
            """.trimIndent(),
            poiId,
        )
    }
}
