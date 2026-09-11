package ca.floo.roadtrip.service.poi.campground

import ca.floo.roadtrip.model.api.poi.PoiCtaSchema
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.metadata.registry.TenantRegistry
import ca.floo.roadtrip.service.availability.provider.AspiraBookingUrl
import ca.floo.roadtrip.service.availability.provider.RecGovBookingUrl
import ca.floo.roadtrip.service.availability.provider.ReservationUrlTemplate
import ca.floo.roadtrip.service.availability.provider.ReserveCaliforniaBookingUrl
import ca.floo.roadtrip.service.etl.vendors.campflare.CampflareUrls

// Backend-computed actions for a POI pin. The drawer reads {url, label, kind}
// verbatim — the FE owns no per-vendor precedence, URL construction, or copy.
private const val INFO_CTA_KIND = "info"
private const val RESERVE_CTA_KIND = "reserve"

/** A deeplink offers one night: arrival and the next morning's checkout. */
private const val DEEPLINK_NIGHTS = 1L

internal class CampgroundCta(
    private val tenants: TenantRegistry,
) {
    private val infoLinkLabels = ExternalInfoLinkLabels(tenants)

    private val providers: List<CampgroundCtaProvider> =
        listOf(
            RecGovCampgroundCtaProvider(tenants),
            AspiraCampgroundCtaProvider(tenants),
            ReserveCaliforniaCampgroundCtaProvider(tenants),
        )

    /** The booking site this pin's reservations flow through, as a person reads it. */
    fun bookingSystem(bookingRef: BookingProviderRef?): String? = bookingRef?.let(tenants::displayName)

    /**
     * [identities] is every vendor the row itself names, so a pin aliased onto
     * another vendor keeps the links its own identities earn.
     */
    fun computeCtas(
        bookingRef: BookingProviderRef?,
        reserveUrl: String?,
        infoUrl: String?,
        dateContext: PoiDateContext,
        identities: List<BookingProviderRef> = emptyList(),
    ): List<PoiCtaSchema> {
        val upstreamUrl = providerUrl(reserveUrl = reserveUrl, infoUrl = infoUrl)
        val primaryCta =
            providers.firstNotNullOfOrNull { it.reserveCta(bookingRef, upstreamUrl, dateContext) }
                ?: infoUrl?.takeIf { it.isNotBlank() }?.let {
                    PoiCtaSchema(url = it, label = infoLinkLabels.forUrl(it), kind = INFO_CTA_KIND)
                }
        val campflare = campflareCta(listOfNotNull(bookingRef) + identities)
        return listOfNotNull(primaryCta, campflare).distinctBy { it.url }
    }

    /**
     * Campflare sells nothing itself, so its public page is appended for every
     * Campflare identity rather than offered as a reserve CTA. An aliased row
     * books through the vendor that sells it and still gets this link second.
     */
    private fun campflareCta(refs: List<BookingProviderRef>): PoiCtaSchema? {
        val campflare = refs.firstNotNullOfOrNull { it as? BookingProviderRef.Campflare } ?: return null
        return PoiCtaSchema(
            url = CampflareUrls.campground(campflare.campgroundId),
            label = tenants.ctaLabel(campflare),
            kind = INFO_CTA_KIND,
        )
    }

    private fun providerUrl(
        reserveUrl: String?,
        infoUrl: String?,
    ): String? =
        reserveUrl
            ?.takeIf { it.isNotBlank() }
            ?: infoUrl?.takeIf { it.isNotBlank() }
}

private interface CampgroundCtaProvider {
    fun reserveCta(
        providerRef: BookingProviderRef?,
        upstreamUrl: String?,
        dateContext: PoiDateContext,
    ): PoiCtaSchema?
}

/**
 * The stored URL is kept only when a rec.gov tenant runs its host; otherwise
 * the link is rebuilt from the facility id. An aliased Campflare row carries a
 * campflare.com `reservation_url`, and labelling that "Reserve on
 * Recreation.gov" sent people to the wrong site.
 */
private class RecGovCampgroundCtaProvider(
    private val tenants: TenantRegistry,
) : CampgroundCtaProvider {
    override fun reserveCta(
        providerRef: BookingProviderRef?,
        upstreamUrl: String?,
        dateContext: PoiDateContext,
    ): PoiCtaSchema? {
        val recGov = providerRef as? BookingProviderRef.RecGov ?: return null
        val stored = upstreamUrl?.takeIf { it.isNotBlank() && isRecGovTenantHost(it) }
        return reserveCta(
            url = stored ?: RecGovBookingUrl.campground(recGov.facilityId),
            label = tenants.ctaLabel(recGov),
        )
    }

    private fun isRecGovTenantHost(url: String): Boolean =
        UrlHosts.extract(url)?.let { tenants.tenantByHost(it)?.provider } == BookingProvider.RECGOV
}

private class AspiraCampgroundCtaProvider(
    private val tenants: TenantRegistry,
) : CampgroundCtaProvider {
    override fun reserveCta(
        providerRef: BookingProviderRef?,
        upstreamUrl: String?,
        dateContext: PoiDateContext,
    ): PoiCtaSchema? {
        val aspira = providerRef as? BookingProviderRef.Aspira ?: return null
        // The registry owns the host. A tenant it does not name gets no link
        // rather than a deeplink built on whatever host a row happened to store.
        val host = tenants.tenant(BookingProvider.ASPIRA, aspira.tenant)?.host ?: return null
        val arrival = dateContext.earliestDate
        val template =
            AspiraBookingUrl.template(host, aspira.transactionLocationId, aspira.mapId, aspira.resourceLocationId)
        return reserveCta(
            url = ReservationUrlTemplate.fill(template, arrival, arrival.plusDays(DEEPLINK_NIGHTS)),
            label = tenants.ctaLabel(aspira),
        )
    }
}

private class ReserveCaliforniaCampgroundCtaProvider(
    private val tenants: TenantRegistry,
) : CampgroundCtaProvider {
    override fun reserveCta(
        providerRef: BookingProviderRef?,
        upstreamUrl: String?,
        dateContext: PoiDateContext,
    ): PoiCtaSchema? {
        val reserveCalifornia = providerRef as? BookingProviderRef.ReserveCalifornia ?: return null
        return reserveCta(
            url = ReserveCaliforniaBookingUrl.park(reserveCalifornia.placeId),
            label = tenants.ctaLabel(reserveCalifornia),
        )
    }
}

private fun reserveCta(
    url: String,
    label: String,
): PoiCtaSchema =
    PoiCtaSchema(
        url = url,
        label = label,
        kind = RESERVE_CTA_KIND,
    )
