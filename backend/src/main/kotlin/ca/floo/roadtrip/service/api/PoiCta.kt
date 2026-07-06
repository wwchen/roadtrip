package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.api.PoiCtaSchema
import ca.floo.roadtrip.repo.PoiDetailRow
import ca.floo.roadtrip.service.reservation.ProviderRefParser
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
    fun bookingSystem(row: PoiDetailRow): String? {
        val providerRef = row.providerRefJson?.let { ProviderRefParser.parse(it) }
        val infoUrl = row.infoUrl?.takeIf { it.isNotBlank() }
        return providers.firstNotNullOfOrNull { it.bookingSystem(providerRef, infoUrl) }
    }

    fun computeCta(row: PoiDetailRow): PoiCtaSchema? {
        val providerRef = (row.ctaProviderRefJson ?: row.providerRefJson)?.let { ProviderRefParser.parse(it) }
        val infoUrl = row.infoUrl?.takeIf { it.isNotBlank() }
        return providers.firstNotNullOfOrNull { it.reserveCta(providerRef, infoUrl) }
            ?: infoUrl?.let {
                PoiCtaSchema(
                    url = it,
                    label = ExternalInfoLinkLabels.forUrl(it),
                    kind = INFO_CTA_KIND,
                )
            }
    }
}
