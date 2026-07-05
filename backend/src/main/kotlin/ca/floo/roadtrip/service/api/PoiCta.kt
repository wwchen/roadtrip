package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.api.PoiCtaSchema
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.repo.PoiDetailRow
import ca.floo.roadtrip.service.reservation.BookingUrlTemplate
import ca.floo.roadtrip.service.reservation.ProviderRefParser
import ca.floo.roadtrip.service.reservation.adapters.aspira.AspiraBookingUrl
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

// Backend-computed primary action for a POI pin. The drawer button reads
// {url, label, kind} verbatim — the FE doesn't own per-vendor precedence
// or URL construction.
//
// Precedence (first match wins):
//   1. provider_ref.RecGov  → recreation.gov campground page (kind=reserve)
//   2. provider_ref.Aspira  → fully-formed dated NextGen deeplink to today/
//      tomorrow, anchored to the park's TZ (stub: America/New_York for
//      everyone). Tenant-aware label.
//   3. provider_ref.ReserveCalifornia → ReserveCalifornia landing page
//   4. info_url             → upstream info page (kind=info, label by host)
//   5. null                 → no usable URL; FE falls back to name search
private const val RECGOV_CAMPGROUND_URL = "https://www.recreation.gov/camping/campgrounds/"
private const val RESERVECALIFORNIA_URL = "https://reservecalifornia.com"

// TODO: per-tenant TZ via YAML once we ingest more parks across more zones.
// For now: every Aspira tenant we run lives close enough to Eastern that an
// EST anchor produces a usable "today/tomorrow" booking page.
private val ASPIRA_ANCHOR_TZ: ZoneId = ZoneId.of("America/New_York")

internal class PoiCta(
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        // Convenience for the route layer — uses system clock.
        val Default: PoiCta = PoiCta()
    }

    // Display name for the booking system that reservations on this pin
    // flow through. Same per-vendor knowledge as computeCta, surfaced as
    // a string for the drawer footer.
    fun bookingSystem(row: PoiDetailRow): String? {
        val providerRef = row.providerRefJson?.let { ProviderRefParser.parse(it) }
        return when (providerRef) {
            is ProviderRef.RecGov -> "Recreation.gov"
            is ProviderRef.Aspira -> {
                val host = row.infoUrl?.let { extractHost(it) }
                aspiraBookingSystemLabel(host)
            }
            is ProviderRef.ReserveAmerica -> "ReserveAmerica"
            is ProviderRef.ReserveCalifornia -> "ReserveCalifornia"
            null -> null
        }
    }

    private fun aspiraBookingSystemLabel(host: String?): String =
        when {
            host == null -> "Aspira NextGen"
            host.endsWith("reservation.pc.gc.ca") || host.endsWith("pc.gc.ca") ->
                "Aspira NextGen (Parks Canada)"
            host.endsWith("camping.bcparks.ca") -> "Aspira NextGen (BC Parks)"
            host.endsWith("washington.goingtocamp.com") -> "Aspira NextGen (WA State Parks)"
            else -> "Aspira NextGen"
        }

    fun computeCta(row: PoiDetailRow): PoiCtaSchema? {
        val providerRef = row.providerRefJson?.let { ProviderRefParser.parse(it) }
        val infoUrl = row.infoUrl?.takeIf { it.isNotBlank() }

        when (providerRef) {
            is ProviderRef.RecGov -> {
                return PoiCtaSchema(
                    url = "$RECGOV_CAMPGROUND_URL${providerRef.recgovId}",
                    label = "Reserve on recreation.gov",
                    kind = "reserve",
                )
            }
            is ProviderRef.Aspira -> {
                // Aspira ETL writes the booking host into info_url
                // (https://$host/). Use that to identify the tenant.
                if (infoUrl == null) return null
                val host = extractHost(infoUrl) ?: return null
                return PoiCtaSchema(
                    url = aspiraDeeplink(host, providerRef),
                    label = aspiraLabelForHost(host),
                    kind = "reserve",
                )
            }
            is ProviderRef.ReserveCalifornia -> {
                return PoiCtaSchema(
                    url = reserveCaliforniaParkUrl(providerRef.placeId),
                    label = "Reserve on ReserveCalifornia",
                    kind = "reserve",
                )
            }
            else -> Unit
        }

        if (infoUrl == null) return null
        return PoiCtaSchema(
            url = infoUrl,
            label = labelForInfoUrl(infoUrl),
            kind = "info",
        )
    }

    // The campground-level "book now" deeplink: the shared Aspira NextGen
    // /create-booking/results scheme ([AspiraBookingUrl]), filled for
    // today→tomorrow anchored to the park's TZ — the booking flow keys on
    // "the park's day." The URL scheme lives in the adapter, never here.
    private fun aspiraDeeplink(
        host: String,
        ref: ProviderRef.Aspira,
    ): String {
        val today = LocalDate.now(clock.withZone(ASPIRA_ANCHOR_TZ))
        val template = AspiraBookingUrl.template(host, ref.transactionLocationId, ref.mapId, ref.resourceLocationId)
        return BookingUrlTemplate.fill(template, today, today.plusDays(1))
    }

    private fun reserveCaliforniaParkUrl(placeId: Long): String = "$RESERVECALIFORNIA_URL/park/$placeId"

    private fun aspiraLabelForHost(host: String): String =
        when {
            host.endsWith("camping.bcparks.ca") -> "Book on BC Parks"
            host.endsWith("washington.goingtocamp.com") -> "Book WA State Park"
            host.endsWith("reservation.pc.gc.ca") || host.endsWith("pc.gc.ca") ->
                "Reserve on parks.canada.ca"
            // Unknown Aspira tenant — better than a generic "Reserve" because
            // the user still sees where the click lands.
            else -> "Reserve on $host"
        }

    // Host-aware label. Picks a recognizable phrase for the upstream the URL
    // points at; falls back to the bare host so the user always sees where
    // the click lands. Pure URL inspection — no per-row state.
    private fun labelForInfoUrl(url: String): String {
        val host = extractHost(url) ?: return "Visit website"
        return when {
            host.endsWith("fs.usda.gov") -> "Park info on fs.usda.gov"
            host.endsWith("nps.gov") -> "Park info on nps.gov"
            host.endsWith("blm.gov") -> "Park info on blm.gov"
            host.endsWith("fws.gov") -> "Park info on fws.gov"
            host.endsWith("usace.army.mil") -> "Park info on usace.army.mil"
            host.endsWith("usbr.gov") -> "Park info on usbr.gov"
            host.endsWith("tva.gov") -> "Park info on tva.gov"
            host.endsWith("recreation.gov") -> "View on recreation.gov"
            host.endsWith("bcparks.ca") -> "Park info on bcparks.ca"
            host.endsWith("albertaparks.ca") -> "Park info on albertaparks.ca"
            host.endsWith("pc.gc.ca") || host.endsWith("parks.canada.ca") -> "Park info on parks.canada.ca"
            host.endsWith("planetfitness.com") -> "Visit planetfitness.com"
            host.endsWith("tesla.com") -> "View on tesla.com"
            else -> "Visit $host"
        }
    }

    private fun extractHost(url: String): String? {
        val schemeIdx = url.indexOf("://").takeIf { it > 0 } ?: return null
        val afterScheme = url.substring(schemeIdx + 3)
        val end = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val hostPort = if (end < 0) afterScheme else afterScheme.substring(0, end)
        val host = hostPort.substringBefore(':').lowercase().trim()
        if (host.isBlank()) return null
        return host.removePrefix("www.")
    }
}
