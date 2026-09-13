package ca.floo.roadtrip.config

/**
 * Caps for the campground search read. Both bound one request's work: how many
 * ids a boundary search may return, and how many summaries one details call may
 * ask for.
 */
data class CampgroundSearchConfig(
    val maxResults: Int,
    val maxDetailIds: Int,
) {
    init {
        require(maxResults >= 1) { "campground-search max-results must be >= 1 (got $maxResults)" }
        require(maxDetailIds >= 1) { "campground-search max-detail-ids must be >= 1 (got $maxDetailIds)" }
    }

    companion object {
        private const val DEFAULT_MAX_RESULTS = 200
        private const val DEFAULT_MAX_DETAIL_IDS = 50

        val default =
            CampgroundSearchConfig(
                maxResults = DEFAULT_MAX_RESULTS,
                maxDetailIds = DEFAULT_MAX_DETAIL_IDS,
            )

        fun fromConfig(config: ConfigSection): CampgroundSearchConfig =
            CampgroundSearchConfig(
                maxResults = config.value("max-results")?.toInt() ?: default.maxResults,
                maxDetailIds = config.value("max-detail-ids")?.toInt() ?: default.maxDetailIds,
            )
    }
}
