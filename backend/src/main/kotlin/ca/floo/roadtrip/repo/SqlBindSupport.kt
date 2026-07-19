package ca.floo.roadtrip.repo

import kotlinx.serialization.json.JsonElement

internal const val EMPTY_JSON_OBJECT: String = "{}"
internal const val EMPTY_JSON_ARRAY: String = "[]"

// Rows per multi-VALUES bulk statement. 500 keeps parameter counts
// (at most ~30 params/row for campgrounds/campsites) well under the
// Postgres protocol limit of 65 535, while amortizing round-trip overhead.
internal const val BULK_CHUNK_SIZE: Int = 500

internal fun jsonObject(value: JsonElement?): String = value?.toString() ?: EMPTY_JSON_OBJECT

internal fun jsonArray(value: JsonElement?): String = value?.toString() ?: EMPTY_JSON_ARRAY

internal fun jsonArrayOrNull(value: JsonElement?): String? = value?.toString()
