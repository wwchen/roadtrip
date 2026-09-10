package ca.floo.roadtrip.model.api.poi

import ca.floo.roadtrip.model.domain.CampgroundRating
import kotlinx.serialization.Serializable

/** The vendor's aggregate visitor rating as the API serves it. */
@Serializable
data class RatingDto(
    val average: Double,
    val count: Int,
) {
    companion object {
        private const val MIN_SERVED_COUNT = 1

        /** An average over no reviews is a vendor placeholder, not a rating, so it is not served. */
        fun from(rating: CampgroundRating): RatingDto? =
            RatingDto(average = rating.average, count = rating.count)
                .takeIf { it.count >= MIN_SERVED_COUNT }
    }
}
