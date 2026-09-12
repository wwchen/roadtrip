package ca.floo.roadtrip.model.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a campground's pin was found. Aspira-backed rows carry no coordinates of
 * their own and join a sibling geometry feed by name, so the pin is only as
 * good as the match: this records which feed supplied it, which index name it
 * resolved to, and the fuzzy score when the match was not exact. Providers
 * whose payload carries its own coordinates leave it null.
 */
@Serializable
data class GeometryProvenance(
    /** "exact" | "fuzzy" | "parent" — the ETL-side match kind's label. */
    @SerialName("match_kind") val matchKind: String,
    /** The geometry input slug that supplied the point. */
    val source: String,
    @SerialName("matched_name") val matchedName: String,
    /** Jaccard token overlap for a fuzzy match; null otherwise. */
    val score: Double? = null,
)
