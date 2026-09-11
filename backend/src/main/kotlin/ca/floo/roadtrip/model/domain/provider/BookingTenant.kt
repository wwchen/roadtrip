package ca.floo.roadtrip.model.domain.provider

/** One host a booking vendor runs, with the name a person books under. */
data class BookingTenant(
    val provider: BookingProvider,
    val code: String?,
    val host: String,
    val displayName: String,
)

/** A booking vendor as the registry declares it. */
data class BookingVendorProfile(
    val provider: BookingProvider,
    val displayName: String,
    val sells: Boolean,
    val tenants: List<BookingTenant>,
)
