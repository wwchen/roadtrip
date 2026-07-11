package ca.floo.roadtrip.models.domain

/**
 * Wide row shape returned by GET /api/pois/{id}. Same projection the bbox
 * endpoint used to ship for every row; now paid for only on pin click.
 */
data class PoiDetailRow(
    val id: Long,
    val source: String,
    val sourceId: String,
    val category: String,
    val subcategory: String?,
    val agency: String? = null,
    val name: String,
    val region: String?,
    val country: String? = null,
    val lng: Double? = null,
    val lat: Double? = null,
    val unitName: String?,
    val reserveUrl: String?,
    val phone: String?,
    val infoUrl: String?,
    val addressJson: String?,
    val providerRefJson: String? = null,
    val geomJson: String,
    val propertiesJson: String,
    val ctaProviderRefJson: String? = null,
    /**
     * Vendors that contributed to this canonical row (from the
     * campground_canonical materialized view's `member_sources` TEXT[]).
     * Empty for non-campground POIs and for campgrounds not yet grouped.
     */
    val memberSources: List<String> = emptyList(),
)
