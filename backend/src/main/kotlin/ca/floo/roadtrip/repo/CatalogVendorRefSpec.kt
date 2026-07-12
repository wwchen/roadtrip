package ca.floo.roadtrip.repo

import kotlinx.serialization.json.JsonElement

internal data class CatalogVendorRefSpec(
    val vendor: String,
    val entityType: String,
    val externalId: String,
    val externalName: String?,
    val sourceUrl: String?,
    val payload: JsonElement?,
)
