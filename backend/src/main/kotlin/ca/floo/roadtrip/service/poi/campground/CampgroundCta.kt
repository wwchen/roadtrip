package ca.floo.roadtrip.service.poi.campground

import ca.floo.roadtrip.models.api.poi.PoiCtaSchema
import ca.floo.roadtrip.models.domain.CampflareUrls
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.BookingUrlTemplate
import ca.floo.roadtrip.service.availability.provider.ProviderRefParser
import ca.floo.roadtrip.service.availability.provider.adapters.aspira.AspiraBookingDisplay
import ca.floo.roadtrip.service.availability.provider.adapters.aspira.AspiraBookingUrl
import ca.floo.roadtrip.service.availability.provider.adapters.recgov.RecGovBookingDisplay
import ca.floo.roadtrip.service.availability.provider.adapters.recgov.RecGovBookingUrl
import ca.floo.roadtrip.service.availability.provider.adapters.reserveamerica.ReserveAmericaBookingDisplay
import ca.floo.roadtrip.service.availability.provider.adapters.reservecalifornia.ReserveCaliforniaBookingDisplay
import ca.floo.roadtrip.service.availability.provider.adapters.reservecalifornia.ReserveCaliforniaBookingUrl
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

// Backend-computed actions for a POI pin. The drawer reads {url, label, kind}
// verbatim — the FE doesn't own per-vendor precedence or URL construction.
private const val INFO_CTA_KIND = "info"
private const val RESERVE_CTA_KIND = "reserve"
private const val CAMPFLARE_CTA_LABEL = "View on Campflare"

internal class CampgroundCta(
    clock: Clock = Clock.systemUTC(),
) {
    private val providers: List<CampgroundCtaProvider> =
        listOf(
            RecGovCampgroundCtaProvider,
            AspiraCampgroundCtaProvider(clock),
            ReserveAmericaCampgroundCtaProvider,
            ReserveCaliforniaCampgroundCtaProvider,
        )

    companion object {
        // Convenience for the route layer — uses system clock.
        val Default: CampgroundCta = CampgroundCta()
    }

    // Display name for the booking system that reservations on this pin
    // flow through. Same per-vendor knowledge as computeCtas, surfaced as
    // a string for the drawer footer.
    fun bookingSystem(
        providerRefJson: String?,
        reserveUrl: String?,
        infoUrl: String?,
    ): String? {
        val providerRef = providerRefJson?.let { ProviderRefParser.parse(it) }
        val upstreamUrl = providerUrl(reserveUrl = reserveUrl, infoUrl = infoUrl)
        return providers.firstNotNullOfOrNull { it.bookingSystem(providerRef, upstreamUrl) }
    }

    fun computeCtas(
        providerRefJson: String?,
        ctaProviderRefJson: String?,
        reserveUrl: String?,
        infoUrl: String?,
    ): List<PoiCtaSchema> {
        val primaryProviderRef = (ctaProviderRefJson ?: providerRefJson)?.let { ProviderRefParser.parse(it) }
        val sourceProviderRef = providerRefJson?.let { ProviderRefParser.parse(it) }
        val primaryCta =
            primaryReserveCta(
                providerRef = primaryProviderRef,
                reserveUrl = reserveUrl,
                infoUrl = infoUrl,
            ) ?: infoUrl?.takeIf { it.isNotBlank() }?.let {
                PoiCtaSchema(
                    url = it,
                    label = ExternalInfoLinkLabels.forUrl(it),
                    kind = INFO_CTA_KIND,
                )
            }
        return listOfNotNull(
            primaryCta,
            campflareCta(sourceProviderRef),
        ).distinctBy { it.url }
    }

    private fun campflareCta(providerRef: ProviderRef?): PoiCtaSchema? {
        val campflare = providerRef as? ProviderRef.Campflare ?: return null
        return PoiCtaSchema(
            url = CampflareUrls.campground(campflare.campgroundId),
            label = CAMPFLARE_CTA_LABEL,
            kind = INFO_CTA_KIND,
        )
    }

    private fun primaryReserveCta(
        providerRef: ProviderRef?,
        reserveUrl: String?,
        infoUrl: String?,
    ): PoiCtaSchema? =
        providers.firstNotNullOfOrNull {
            it.reserveCta(providerRef, providerUrl(reserveUrl = reserveUrl, infoUrl = infoUrl))
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
    fun bookingSystem(
        providerRef: ProviderRef?,
        infoUrl: String?,
    ): String? = null

    fun reserveCta(
        providerRef: ProviderRef?,
        infoUrl: String?,
    ): PoiCtaSchema? = null
}

private object RecGovCampgroundCtaProvider : CampgroundCtaProvider {
    override fun bookingSystem(
        providerRef: ProviderRef?,
        infoUrl: String?,
    ): String? = (providerRef as? ProviderRef.RecGov)?.let { RecGovBookingDisplay.BOOKING_SYSTEM_LABEL }

    override fun reserveCta(
        providerRef: ProviderRef?,
        infoUrl: String?,
    ): PoiCtaSchema? {
        val recgov = providerRef as? ProviderRef.RecGov ?: return null
        return reserveCta(
            url = RecGovBookingUrl.campground(recgov.recgovId),
            label = RecGovBookingDisplay.CAMPGROUND_CTA_LABEL,
        )
    }
}

private class AspiraCampgroundCtaProvider(
    private val clock: Clock,
) : CampgroundCtaProvider {
    override fun bookingSystem(
        providerRef: ProviderRef?,
        infoUrl: String?,
    ): String? {
        providerRef as? ProviderRef.Aspira ?: return null
        return AspiraBookingDisplay.bookingSystemLabel(infoUrl?.let(UrlHosts::extract))
    }

    override fun reserveCta(
        providerRef: ProviderRef?,
        infoUrl: String?,
    ): PoiCtaSchema? {
        val aspira = providerRef as? ProviderRef.Aspira ?: return null
        val host = infoUrl?.let(UrlHosts::extract) ?: return null
        return reserveCta(
            url = deeplink(host, aspira),
            label = AspiraBookingDisplay.ctaLabel(host),
        )
    }

    private fun deeplink(
        host: String,
        ref: ProviderRef.Aspira,
    ): String {
        val today = LocalDate.now(clock.withZone(ASPIRA_ANCHOR_TZ))
        val template = AspiraBookingUrl.template(host, ref.transactionLocationId, ref.mapId, ref.resourceLocationId)
        return BookingUrlTemplate.fill(template, today, today.plusDays(1))
    }

    private companion object {
        // TODO: per-tenant TZ via YAML once we ingest more parks across more zones.
        // For now, every Aspira tenant we run lives close enough to Eastern that
        // an EST anchor produces a usable today/tomorrow booking page.
        val ASPIRA_ANCHOR_TZ: ZoneId = ZoneId.of("America/New_York")
    }
}

private object ReserveAmericaCampgroundCtaProvider : CampgroundCtaProvider {
    override fun bookingSystem(
        providerRef: ProviderRef?,
        infoUrl: String?,
    ): String? = (providerRef as? ProviderRef.ReserveAmerica)?.let { ReserveAmericaBookingDisplay.BOOKING_SYSTEM_LABEL }
}

private object ReserveCaliforniaCampgroundCtaProvider : CampgroundCtaProvider {
    override fun bookingSystem(
        providerRef: ProviderRef?,
        infoUrl: String?,
    ): String? = (providerRef as? ProviderRef.ReserveCalifornia)?.let { ReserveCaliforniaBookingDisplay.BOOKING_SYSTEM_LABEL }

    override fun reserveCta(
        providerRef: ProviderRef?,
        infoUrl: String?,
    ): PoiCtaSchema? {
        val reserveCalifornia = providerRef as? ProviderRef.ReserveCalifornia ?: return null
        return reserveCta(
            url = ReserveCaliforniaBookingUrl.park(reserveCalifornia.placeId),
            label = ReserveCaliforniaBookingDisplay.PARK_CTA_LABEL,
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
