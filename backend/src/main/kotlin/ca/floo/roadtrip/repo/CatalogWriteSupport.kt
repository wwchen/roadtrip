package ca.floo.roadtrip.repo

import kotlinx.serialization.json.JsonElement
import org.jooq.DSLContext

internal const val CAMPGROUND_ENTITY: String = "campground"
internal const val CAMPSITE_ENTITY: String = "campsite"
internal const val EMPTY_JSON_OBJECT: String = "{}"
internal const val EMPTY_JSON_ARRAY: String = "[]"

// Rows per multi-VALUES bulk statement. 500 keeps parameter counts
// (at most ~30 params/row for campgrounds/campsites) well under the
// Postgres protocol limit of 65 535, while amortizing round-trip overhead.
internal const val BULK_CHUNK_SIZE: Int = 500

internal data class CatalogVendorRefKey(
    val vendor: String,
    val entityType: String,
    val externalId: String,
)

internal data class CatalogVendorRefSpec(
    val vendor: String,
    val entityType: String,
    val externalId: String,
    val externalName: String?,
    val sourceUrl: String?,
    val payload: JsonElement?,
)

internal class CatalogVendorRefRepo(
    private val ctx: DSLContext,
) {
    /**
     * Upsert every distinct vendor_ref natural key in one pass. Returns a
     * map of (vendor, entity_type, external_id) -> id populated from both
     * newly inserted and previously existing rows.
     */
    fun bulkUpsertVendorRefs(specs: List<CatalogVendorRefSpec>): Map<CatalogVendorRefKey, Long> {
        if (specs.isEmpty()) return emptyMap()
        val deduped = specs.distinctBy { CatalogVendorRefKey(it.vendor, it.entityType, it.externalId) }
        val result = HashMap<CatalogVendorRefKey, Long>(deduped.size)
        for (chunk in deduped.chunked(BULK_CHUNK_SIZE)) {
            val placeholders = chunk.joinToString(", ") { "(?, ?, ?, ?, ?, ?::jsonb, now(), NULL)" }
            val sql =
                """
                INSERT INTO vendor_refs
                  (vendor, entity_type, external_id, external_name, source_url, payload, updated_at, deleted_at)
                VALUES $placeholders
                ON CONFLICT (vendor, entity_type, external_id) WHERE deleted_at IS NULL
                DO UPDATE SET
                  external_name = EXCLUDED.external_name,
                  source_url    = EXCLUDED.source_url,
                  payload       = EXCLUDED.payload,
                  updated_at    = now(),
                  deleted_at    = NULL
                RETURNING id, vendor, entity_type, external_id
                """.trimIndent()
            val params = mutableListOf<Any?>()
            for (spec in chunk) {
                params += spec.vendor
                params += spec.entityType
                params += spec.externalId
                params += spec.externalName
                params += spec.sourceUrl
                params += jsonObject(spec.payload)
            }
            val rows = ctx.fetch(sql, *params.toTypedArray())
            for (row in rows) {
                val key =
                    CatalogVendorRefKey(
                        vendor = row.get("vendor", String::class.java),
                        entityType = row.get("entity_type", String::class.java),
                        externalId = row.get("external_id", String::class.java),
                    )
                result[key] = row.get("id", Long::class.java)
            }
        }
        return result
    }
}

internal class PoiCatalogRepo(
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
}

internal fun jsonObject(value: JsonElement?): String = value?.toString() ?: EMPTY_JSON_OBJECT

internal fun jsonArray(value: JsonElement?): String = value?.toString() ?: EMPTY_JSON_ARRAY

internal fun jsonArrayOrNull(value: JsonElement?): String? = value?.toString()
