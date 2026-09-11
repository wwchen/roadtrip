package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.CatalogColumnJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

internal const val EMPTY_JSON_OBJECT: String = "{}"
internal const val EMPTY_JSON_ARRAY: String = "[]"

// Rows per multi-VALUES bulk statement. 500 keeps parameter counts
// (at most ~30 params/row for campgrounds/campsites) well under the
// Postgres protocol limit of 65 535, while amortizing round-trip overhead.
internal const val BULK_CHUNK_SIZE: Int = 500

// Catalog upsert methods accept one bounded logical batch. The ETL
// orchestrator owns splitting large import streams into batches of this size.
internal const val MAX_CATALOG_UPSERT_BATCH_SIZE: Int = 1_000

internal fun requireCatalogBatchWithinLimit(
    label: String,
    size: Int,
) {
    require(size <= MAX_CATALOG_UPSERT_BATCH_SIZE) {
        "$label batch size $size exceeds max $MAX_CATALOG_UPSERT_BATCH_SIZE"
    }
}

internal fun jsonObject(value: JsonElement?): String = value?.toString() ?: EMPTY_JSON_OBJECT

/** A typed JSONB column that must be SQL NULL when absent, not the empty object. */
internal inline fun <reified T : Any> nullableJsonObject(value: T?): String? = value?.let { CatalogColumnJson.json.encodeToString(it) }

internal fun jsonArray(value: JsonElement?): String = value?.toString() ?: EMPTY_JSON_ARRAY
