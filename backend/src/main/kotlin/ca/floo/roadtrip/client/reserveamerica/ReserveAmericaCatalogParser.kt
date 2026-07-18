package ca.floo.roadtrip.client.reserveamerica

/**
 * Derives the site roster (catalog) from the same `campsiteCalendar.do` HTML
 * the availability adapter scrapes. Shares row-splitting with
 * [ReserveAmericaAvailabilityParser] via [ReserveAmericaAvailabilityParser.siteRows],
 * so the emitted [CatalogSite.siteId] is byte-for-byte the id the availability
 * path keys on — catalog rows bind to availability by construction.
 *
 * `loop`/`site_type` are intentionally not extracted: the calendar's loopName
 * is a pagination bucket ("Sites 036-049"), not a real loop, and site type is
 * only present as brittle per-cell attribute markup.
 */
object ReserveAmericaCatalogParser {
    data class CatalogSite(
        val parkId: String,
        val siteId: String,
        val name: String,
    )

    private val siteIdRegex = Regex("""siteId=(\d+)""")
    private val parkIdRegex = Regex("""parkId=(\d+)""")
    private val labelTextRegex = Regex(""">([^<]+)</a>""")

    fun parse(html: String): List<CatalogSite> =
        ReserveAmericaAvailabilityParser.siteRows(html).mapNotNull { row ->
            val siteId = siteIdRegex.find(row)?.groupValues?.get(1) ?: return@mapNotNull null
            val parkId = parkIdRegex.find(row)?.groupValues?.get(1) ?: return@mapNotNull null
            val name =
                labelTextRegex
                    .find(row)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    .orEmpty()
                    .ifEmpty { siteId }
            CatalogSite(parkId = parkId, siteId = siteId, name = name)
        }
}
