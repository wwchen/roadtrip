package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.bookingIdentities
import ca.floo.roadtrip.model.domain.bookingRef
import ca.floo.roadtrip.model.domain.bookingRefFor
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.metadata.registry.TenantRegistry
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider

/**
 * The identity a row books through, for the drawer and the campsite rows alike.
 *
 * The rule is the registry's `sells`, not this process's booking adapters: a
 * Campflare row that rec.gov also sells opens on rec.gov whether or not the
 * ATC companion happens to be wired here, so a pin renders the same in every
 * environment.
 */
internal class BookingIdentityResolver(
    private val tenants: TenantRegistry,
) {
    fun forCampground(
        campground: Campground,
        servingProvider: AvailabilityProvider?,
        declaredPrimary: BookingProviderRef? = campground.bookingRef(),
    ): BookingProviderRef? =
        campground.bookingIdentities().firstOrNull { tenants.sells(it.provider) }
            ?: servingProvider?.claimedRef(campground)
            ?: declaredPrimary

    /**
     * The campsite row's identity. The site names its own vendors; the tenant
     * that names the site a person books on lives on the campground's ref for
     * that same vendor, so the two are paired before falling back to the
     * campground's own rule.
     */
    fun forCampsite(resolved: ResolvedAvailabilityTarget): BookingProviderRef? =
        resolved.campsite
            .bookingIdentities()
            .firstOrNull { tenants.sells(it.provider) }
            ?.let { resolved.campground.bookingRefFor(it.provider) }
            ?: forCampground(resolved.campground, resolved.provider)
}
