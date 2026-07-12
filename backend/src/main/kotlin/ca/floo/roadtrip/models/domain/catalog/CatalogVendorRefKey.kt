package ca.floo.roadtrip.models.domain.catalog

internal data class CatalogVendorRefKey(
    val vendor: String,
    val entityType: String,
    val externalId: String,
)
