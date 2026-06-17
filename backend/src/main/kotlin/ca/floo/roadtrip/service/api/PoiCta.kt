package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.models.api.PoiCtaSchema
import ca.floo.roadtrip.repo.PoiDetailRow
import ca.floo.roadtrip.service.booking.ProviderRefParser

// Backend-computed primary action for a POI pin. The drawer button reads
// {url, label, kind} verbatim — the FE doesn't own per-vendor precedence.
//
// Precedence (first match wins):
//   1. provider_ref.RecGov  → recreation.gov campground page (kind=reserve)
//   2. provider_ref.Aspira  → null (FE owns the dated deeplink; date math
//      happens in the browser's local TZ. The label still comes from here).
//   3. info_url             → upstream info page (kind=info, label by host)
//   4. null                 → no usable URL; FE falls back to name search
internal object PoiCta {
    private const val RECGOV_CAMPGROUND_URL = "https://www.recreation.gov/camping/campgrounds/"

    fun computeCta(row: PoiDetailRow): PoiCtaSchema? {
        val providerRef = row.providerRefJson?.let { ProviderRefParser.parse(it) }

        when (providerRef) {
            is ProviderRef.RecGov -> {
                return PoiCtaSchema(
                    url = "$RECGOV_CAMPGROUND_URL${providerRef.recgovId}",
                    label = "Reserve on recreation.gov",
                    kind = "reserve",
                )
            }
            is ProviderRef.Aspira -> {
                // Deeplink construction needs today/tomorrow in the user's
                // timezone — keep it on the FE. Backend just supplies the
                // label so the FE doesn't repeat the host→tenant mapping.
                return null
            }
            else -> Unit
        }

        val infoUrl = row.infoUrl?.takeIf { it.isNotBlank() } ?: return null
        return PoiCtaSchema(
            url = infoUrl,
            label = labelForInfoUrl(infoUrl),
            kind = "info",
        )
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
