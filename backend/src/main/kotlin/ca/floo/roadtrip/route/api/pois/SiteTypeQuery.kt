package ca.floo.roadtrip.route.api.pois

import ca.floo.roadtrip.model.domain.CampsiteKind

/**
 * A `site_type` value set, or the first value outside the wire vocabulary.
 * Shared by [CampsiteRoutes] (query params) and [BulkAvailabilityRoutes]
 * (a request-body field) so neither route parses exception text to find
 * the offending value.
 */
internal sealed interface SiteTypeQuery {
    data class Parsed(
        val kinds: List<CampsiteKind>,
    ) : SiteTypeQuery

    data class Unknown(
        val value: String,
    ) : SiteTypeQuery
}

/** Parses raw `site_type` values against the wire vocabulary, stopping at the first miss. */
internal fun parseSiteTypes(values: Iterable<String>): SiteTypeQuery {
    val kinds = mutableListOf<CampsiteKind>()
    for (raw in values) {
        kinds += CampsiteKind.fromWire(raw) ?: return SiteTypeQuery.Unknown(raw)
    }
    return SiteTypeQuery.Parsed(kinds)
}
