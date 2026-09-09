package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import kotlinx.serialization.Serializable

@Serializable
data class BookingRefDto(
    val provider: String,
    val ref: String,
) {
    companion object {
        fun from(ref: BookingProviderRef): BookingRefDto = BookingRefDto(provider = ref.provider.id, ref = ref.serialize())
    }
}
