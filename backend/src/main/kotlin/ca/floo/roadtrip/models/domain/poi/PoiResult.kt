package ca.floo.roadtrip.models.domain.poi

/**
 * Outcome of a sampled bbox fetch. `truncated` is true whenever the raw
 * count exceeded the global cap, so the frontend can show "zoom in for more".
 */
data class PoiResult(
    val rows: List<PoiRow>,
    val truncated: Boolean,
)
