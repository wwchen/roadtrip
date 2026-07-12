package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.api.PoiCtaSchema
import ca.floo.roadtrip.service.availability.provider.ProviderRefParser
import java.time.Clock

// Backend-computed primary action for a POI pin. The drawer button reads
// {url, label, kind} verbatim — the FE doesn't own per-vendor precedence
// or URL construction.
private const val INFO_CTA_KIND = "info"

internal class PoiCta(
    clock: Clock = Clock.systemUTC(),
) {
    private val providers: List<PoiCtaProvider> =
        listOf(
            RecGovPoiCtaProvider,
            AspiraPoiCtaProvider(clock),
            ReserveAmericaPoiCtaProvider,
            ReserveCaliforniaPoiCtaProvider,
        )

    companion object {
        // Convenience for the route layer — uses system clock.
        val Default: PoiCta = PoiCta()
    }

    // Display name for the booking system that reservations on this pin
    // flow through. Same per-vendor knowledge as computeCta, surfaced as
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

    fun computeCta(
        providerRefJson: String?,
        ctaProviderRefJson: String?,
        reserveUrl: String?,
        infoUrl: String?,
    ): PoiCtaSchema? {
        val providerRef = (ctaProviderRefJson ?: providerRefJson)?.let { ProviderRefParser.parse(it) }
        return providers.firstNotNullOfOrNull {
            it.reserveCta(providerRef, providerUrl(reserveUrl = reserveUrl, infoUrl = infoUrl))
        } ?: infoUrl?.takeIf { it.isNotBlank() }?.let {
            PoiCtaSchema(
                url = it,
                label = ExternalInfoLinkLabels.forUrl(it),
                kind = INFO_CTA_KIND,
            )
        }
    }

    private fun providerUrl(
        reserveUrl: String?,
        infoUrl: String?,
    ): String? =
        reserveUrl
            ?.takeIf { it.isNotBlank() }
            ?: infoUrl?.takeIf { it.isNotBlank() }
}
