package ca.floo.roadtrip.repo

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

internal fun jsonArray(value: JsonElement?): String = value?.toString() ?: EMPTY_JSON_ARRAY

internal fun jsonArrayOrNull(value: JsonElement?): String? = value?.toString()
