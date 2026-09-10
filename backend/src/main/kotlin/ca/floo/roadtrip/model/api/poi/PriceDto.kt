package ca.floo.roadtrip.model.api.poi

import ca.floo.roadtrip.model.domain.CampgroundPrice
import kotlinx.serialization.Serializable

/** The campground's nightly price range as the API serves it. */
@Serializable
data class PriceDto(
    val minimum: Double? = null,
    val maximum: Double? = null,
    val currency: String? = null,
) {
    companion object {
        fun from(price: CampgroundPrice): PriceDto =
            PriceDto(
                minimum = price.minimum,
                maximum = price.maximum,
                currency = price.currency,
            )
    }
}
