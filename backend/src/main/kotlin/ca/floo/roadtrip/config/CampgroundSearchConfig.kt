package ca.floo.roadtrip.config

/**
 * Caps for the campground search read: how many ids a boundary search may return,
 * how many summaries one details call may ask for, and how large a boundary the
 * search will run against PostGIS.
 */
data class CampgroundSearchConfig(
    val maxResults: Int,
    val maxDetailIds: Int,
    val maxBoundaryAreaSqDeg: Double,
) {
    init {
        require(maxResults >= 1) { "campground-search max-results must be >= 1 (got $maxResults)" }
        require(maxDetailIds >= 1) { "campground-search max-detail-ids must be >= 1 (got $maxDetailIds)" }
        require(maxBoundaryAreaSqDeg > 0) {
            "campground-search max-boundary-area-sq-deg must be > 0 (got $maxBoundaryAreaSqDeg)"
        }
    }

    companion object {
        private const val DEFAULT_MAX_RESULTS = 200
        private const val DEFAULT_MAX_DETAIL_IDS = 50
        private const val DEFAULT_MAX_BOUNDARY_AREA_SQ_DEG = 500.0

        val default =
            CampgroundSearchConfig(
                maxResults = DEFAULT_MAX_RESULTS,
                maxDetailIds = DEFAULT_MAX_DETAIL_IDS,
                maxBoundaryAreaSqDeg = DEFAULT_MAX_BOUNDARY_AREA_SQ_DEG,
            )

        fun fromConfig(config: ConfigSection): CampgroundSearchConfig =
            CampgroundSearchConfig(
                maxResults = config.value("max-results")?.toInt() ?: default.maxResults,
                maxDetailIds = config.value("max-detail-ids")?.toInt() ?: default.maxDetailIds,
                maxBoundaryAreaSqDeg =
                    config.value("max-boundary-area-sq-deg")?.toDouble() ?: default.maxBoundaryAreaSqDeg,
            )
    }
}
