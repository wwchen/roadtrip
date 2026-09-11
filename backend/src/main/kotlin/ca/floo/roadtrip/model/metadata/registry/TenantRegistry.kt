package ca.floo.roadtrip.model.metadata.registry

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.BookingTenant
import ca.floo.roadtrip.model.domain.provider.BookingVendorProfile

/** The verb is ours; the name is data. */
private const val RESERVE_VERB = "Reserve on"
private const val VIEW_VERB = "View on"
private const val HOST_WWW_PREFIX = "www."

/**
 * The `booking_providers` section, projected for lookup.
 *
 * Every vendor fact a person reads — the name, the tenant's name, whether the
 * vendor sells — lives here rather than in an adapter, a `*BookingDisplay`
 * object, or a frontend table. Built from a validated [PoiRegistry], so
 * [profile] may fail loudly on a provider the section does not name.
 */
class TenantRegistry private constructor(
    private val profiles: Map<BookingProvider, BookingVendorProfile>,
) {
    private val byHost: Map<String, BookingTenant> =
        profiles.values
            .flatMap { it.tenants }
            .associateBy { normalizeHost(it.host) }

    fun profile(provider: BookingProvider): BookingVendorProfile =
        profiles[provider] ?: error("booking_providers has no row for '${provider.id}'")

    fun tenantsOf(provider: BookingProvider): List<BookingTenant> = profile(provider).tenants

    fun tenant(
        provider: BookingProvider,
        code: String?,
    ): BookingTenant? = tenantsOf(provider).firstOrNull { it.code == code }

    fun tenantByHost(host: String): BookingTenant? = byHost[normalizeHost(host)]

    fun displayName(provider: BookingProvider): String = profile(provider).displayName

    /** The ref's tenant name when the ref names a known tenant, else the vendor name. */
    fun displayName(ref: BookingProviderRef): String = tenant(ref.provider, tenantCodeOf(ref))?.displayName ?: displayName(ref.provider)

    fun sells(provider: BookingProvider): Boolean = profile(provider).sells

    fun ctaLabel(ref: BookingProviderRef): String = label(sells(ref.provider), displayName(ref))

    /** The same label by host; null for a host no vendor runs. */
    fun linkLabel(host: String): String? = tenantByHost(host)?.let { label(sells(it.provider), it.displayName) }

    private fun label(
        sells: Boolean,
        name: String,
    ): String = if (sells) "$RESERVE_VERB $name" else "$VIEW_VERB $name"

    companion object {
        fun from(registry: PoiRegistry): TenantRegistry = from(registry.bookingProviders)

        fun from(entries: List<BookingProviderEntry>): TenantRegistry {
            val profiles =
                entries.associate { entry ->
                    entry.id to
                        BookingVendorProfile(
                            provider = entry.id,
                            displayName = entry.displayName,
                            sells = entry.sells,
                            tenants =
                                entry.tenants.map { tenant ->
                                    BookingTenant(
                                        provider = entry.id,
                                        code = tenant.code,
                                        host = tenant.host,
                                        displayName = tenant.displayName ?: entry.displayName,
                                    )
                                },
                        )
                }
            val missing = BookingProvider.entries.filter { it !in profiles }
            require(missing.isEmpty()) {
                "booking_providers is missing a row for ${missing.joinToString { "'${it.id}'" }}"
            }
            return TenantRegistry(profiles)
        }

        /** Only Aspira and ReserveAmerica refs carry a tenant key. */
        internal fun tenantCodeOf(ref: BookingProviderRef): String? =
            when (ref) {
                is BookingProviderRef.Aspira -> ref.tenant
                is BookingProviderRef.ReserveAmerica -> ref.contractCode
                else -> null
            }

        internal fun normalizeHost(host: String): String = host.trim().lowercase().removePrefix(HOST_WWW_PREFIX)
    }
}
