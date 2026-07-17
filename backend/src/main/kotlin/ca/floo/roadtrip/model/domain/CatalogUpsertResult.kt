package ca.floo.roadtrip.model.domain

data class CatalogUpsertResult(
    val runId: Long,
    val seenCount: Int,
    val upsertedCount: Int,
    val skippedCount: Int = 0,
    val sweptCount: Int = 0,
)
