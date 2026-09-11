package ca.floo.roadtrip.service.poi.campground

import ca.floo.roadtrip.model.metadata.registry.TenantRegistry

private const val UNNAMED_LINK_LABEL = "Visit website"

/**
 * The label for an external link on a pin.
 *
 * A host a booking vendor runs is named by the registry, so a stored
 * recreation.gov or campflare.com URL reads the vendor's registered name.
 * The rest are agencies — who manages the park, not where you book — and stay
 * a table here.
 */
internal class ExternalInfoLinkLabels(
    private val tenants: TenantRegistry,
) {
    fun forUrl(url: String): String {
        val host = UrlHosts.extract(url) ?: return UNNAMED_LINK_LABEL
        tenants.linkLabel(host)?.let { return it }
        return when {
            host.endsWith("fs.usda.gov") -> "Park info on fs.usda.gov"
            host.endsWith("nps.gov") -> "Park info on nps.gov"
            host.endsWith("blm.gov") -> "Park info on blm.gov"
            host.endsWith("fws.gov") -> "Park info on fws.gov"
            host.endsWith("usace.army.mil") -> "Park info on usace.army.mil"
            host.endsWith("usbr.gov") -> "Park info on usbr.gov"
            host.endsWith("tva.gov") -> "Park info on tva.gov"
            host.endsWith("bcparks.ca") -> "Park info on bcparks.ca"
            host.endsWith("albertaparks.ca") -> "Park info on albertaparks.ca"
            host.endsWith("pc.gc.ca") || host.endsWith("parks.canada.ca") -> "Park info on parks.canada.ca"
            host.endsWith("planetfitness.com") -> "Visit planetfitness.com"
            host.endsWith("tesla.com") -> "View on tesla.com"
            else -> "Visit $host"
        }
    }
}
