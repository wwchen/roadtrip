package ca.floo.roadtrip.route.common

import ca.floo.roadtrip.model.domain.CampsiteKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val BAD_REQUEST_ERROR = "bad_request"

internal const val SITE_TYPE_FILTER_KEY = "site_type"

/** Built from the enum so the docs cannot drift from what the parser accepts. */
internal val siteTypeWireList = CampsiteKind.entries.joinToString(", ") { it.wire }

internal fun unknownSiteTypeDetail(value: String): String = "unknown site_type '$value'; accepted values: $siteTypeWireList"

/**
 * A `site_type` value set, or the first value outside the wire vocabulary.
 * Shared by the campsite routes (query params), bulk availability (a
 * request-body field) and the watch routes (a `campsite_filters` key) so no
 * caller parses exception text to find the offending value.
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

/**
 * The `bad_request` detail for a stored watch filter, or null when the filter is
 * one this build can still read back. A `site_type` that is neither a string nor
 * an array of strings has no wire value to name, so its JSON stands in.
 */
internal fun siteTypeFilterError(filters: JsonObject): String? {
    val raw = filters[SITE_TYPE_FILTER_KEY] ?: return null
    val elements = if (raw is JsonArray) raw else listOf(raw)
    val values = mutableListOf<String>()
    for (element in elements) {
        values +=
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return unknownSiteTypeDetail(element.toString())
    }
    return (parseSiteTypes(values) as? SiteTypeQuery.Unknown)?.let { unknownSiteTypeDetail(it.value) }
}
