package ca.floo.roadtrip.model.metadata.registry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val USCAMPGROUNDS_CSV_FORMAT = "uscampgrounds_csv"
private const val BCPARKS_STRAPI_FORMAT = "bcparks_strapi"
private const val ARCGIS_CENTROIDS_FORMAT = "arcgis_centroids"
private const val GEOJSON_POINTS_FORMAT = "geojson_points"

/**
 * A geometry-joined ETL row's declaration: which sibling feeds carry the
 * coordinates its own vendor payload lacks, in which preference order, and how
 * leaf names are resolved against them.
 */
@Serializable
data class GeometryPolicy(
    /** Preference order: the first source to claim a normalized name keeps it. */
    val sources: List<GeometrySourceSpec>,
    val match: MatchPolicy = MatchPolicy(),
)

/**
 * One geometry feed. [state] is honoured only by [GeometryFormat.USCAMPGROUNDS_CSV]
 * and [nameProperty] only by [GeometryFormat.GEOJSON_POINTS]; declaring either
 * elsewhere is a boot error rather than a silently dropped filter.
 */
@Serializable
data class GeometrySourceSpec(
    val input: String,
    val format: GeometryFormat,
    @SerialName("name_property") val nameProperty: String? = null,
    val state: String? = null,
)

/** One member, one parser class. [wire] is the spelling the YAML uses. */
@Serializable
enum class GeometryFormat(
    val wire: String,
) {
    @SerialName(USCAMPGROUNDS_CSV_FORMAT)
    USCAMPGROUNDS_CSV(USCAMPGROUNDS_CSV_FORMAT),

    @SerialName(BCPARKS_STRAPI_FORMAT)
    BCPARKS_STRAPI(BCPARKS_STRAPI_FORMAT),

    @SerialName(ARCGIS_CENTROIDS_FORMAT)
    ARCGIS_CENTROIDS(ARCGIS_CENTROIDS_FORMAT),

    @SerialName(GEOJSON_POINTS_FORMAT)
    GEOJSON_POINTS(GEOJSON_POINTS_FORMAT),
}

/** The match ladder's knobs: exact name, then Jaccard overlap, then the parent park's name. */
@Serializable
data class MatchPolicy(
    @SerialName("fuzzy_threshold") val fuzzyThreshold: Double = DEFAULT_FUZZY_THRESHOLD,
    @SerialName("parent_fallback") val parentFallback: Boolean = false,
) {
    companion object {
        /** Minimum Jaccard token overlap for a fuzzy name match. */
        const val DEFAULT_FUZZY_THRESHOLD: Double = 0.5
    }
}
