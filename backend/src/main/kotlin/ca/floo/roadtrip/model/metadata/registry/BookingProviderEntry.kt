package ca.floo.roadtrip.model.metadata.registry

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row in the `booking_providers` section: a booking vendor, the name a
 * person calls it, whether a person books on its own site, and the hosts it
 * runs. One row per [BookingProvider] member — the boot validator enforces it.
 */
@Serializable
data class BookingProviderEntry(
    val id: BookingProvider,
    @SerialName("display_name") val displayName: String,
    val sells: Boolean,
    val tenants: List<TenantEntry> = emptyList(),
)

/**
 * One host a vendor runs. [code] is the tenant key as it is stored in
 * `booking_provider_ref` and as ETL rows name it in `args.tenant` /
 * `args.contract`; single-tenant vendors have one row with no code.
 * [displayName] absent means the vendor's own name.
 */
@Serializable
data class TenantEntry(
    val code: String? = null,
    val host: String,
    @SerialName("display_name") val displayName: String? = null,
)
