package ca.floo.roadtrip.model.domain.catalog

internal data class CatalogVendorRefKey(
    val vendor: String,
    val entityType: String,
    val externalId: String,
)
