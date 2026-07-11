package ca.floo.roadtrip.models.domain

import kotlinx.serialization.json.JsonElement

data class CatalogVendorRefUpsertCandidate(
    val vendor: String,
    val vendorRefId: String,
    val sourceUrl: String? = null,
    val payload: JsonElement? = null,
)
