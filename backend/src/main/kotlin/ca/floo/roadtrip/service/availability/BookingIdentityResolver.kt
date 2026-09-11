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
    ): BookingProviderRef? =
        campground.bookingIdentities().firstOrNull { tenants.sells(it.provider) }
            ?: servingProvider?.claimedRef(campground)
            ?: campground.bookingRef()

    /**
     * The campsite row's identity. The site names its own vendors; the tenant
     * that names the site a person books on lives on the campground's ref for
     * that same vendor, so the two are paired where the campground names one.
     * Where it does not, the site's own ref stands: a Campflare campground
     * whose sites carry rec.gov links still books on rec.gov, and naming the
     * campground instead would label the button after a site the link does not
     * open.
     */
    fun forCampsite(resolved: ResolvedAvailabilityTarget): BookingProviderRef? =
        resolved.campsite
            .bookingIdentities()
            .firstOrNull { tenants.sells(it.provider) }
            ?.let { alias ->
                resolved.campground.bookingRefFor(alias.provider)
                    ?: BookingProviderRef.parse(alias.provider, alias.ref)
            }
            ?: forCampground(resolved.campground, resolved.provider)

    /**
     * The booking site a campsite row names. [resolved] is null when no
     * *enabled* availability provider claims the campground; the row still
     * books somewhere, so it falls back to the campground's own identity
     * rather than going silent in that environment.
     */
    fun bookingSiteName(
        resolved: ResolvedAvailabilityTarget?,
        campground: Campground,
    ): String? =
        when (resolved) {
            null -> forCampground(campground, servingProvider = null)
            else -> forCampsite(resolved)
        }?.let(tenants::displayName)
}
