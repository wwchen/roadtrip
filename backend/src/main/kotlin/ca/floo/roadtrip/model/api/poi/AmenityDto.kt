package ca.floo.roadtrip.model.api.poi

import ca.floo.roadtrip.model.domain.AmenityKey
import ca.floo.roadtrip.model.domain.CampgroundAmenity
import kotlinx.serialization.Serializable

/**
 * One campground amenity as the API serves it. The label is resolved here so
 * the amenity vocabulary lives in one place and the frontend renders `label`
 * verbatim — an absent amenity already reads "No showers" on the wire.
 */
@Serializable
data class AmenityDto(
    val key: String,
    val label: String,
    val present: Boolean,
    val detail: String? = null,
) {
    companion object {
        private const val TOILETS_LABEL_FORMAT = "%s toilets"

        fun from(amenity: CampgroundAmenity): AmenityDto =
            AmenityDto(
                key = amenity.key.wire,
                label = label(amenity),
                present = amenity.present,
                detail = amenity.detail,
            )

        private fun label(amenity: CampgroundAmenity): String {
            val key = amenity.key
            val detail = amenity.detail?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                key == AmenityKey.OTHER -> detail ?: key.label
                key == AmenityKey.TOILETS && detail != null -> TOILETS_LABEL_FORMAT.format(detail.capitalizeFirst())
                !amenity.present -> key.negativeLabel ?: key.label
                else -> key.label
            }
        }

        private fun String.capitalizeFirst(): String = replaceFirstChar { it.uppercaseChar() }
    }
}
