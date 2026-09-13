package ca.floo.roadtrip.fixtures

data class CatalogPoiFixture(
    val poiId: Long,
    val catalogId: Long,
    val poiType: String,
) {
    /** Alias for campground fixtures: `catalogId` is the campground id when `poiType == "campground"`. */
    val campgroundId: Long get() = catalogId
}
