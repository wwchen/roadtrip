package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.api.PoiCtaSchema
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.BookingUrlTemplate
import ca.floo.roadtrip.service.availability.provider.adapters.aspira.AspiraBookingDisplay
import ca.floo.roadtrip.service.availability.provider.adapters.aspira.AspiraBookingUrl
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

internal class AspiraPoiCtaProvider(
    private val clock: Clock,
) : PoiCtaProvider {
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
