package ca.floo.roadtrip.clients.reserveamerica

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

    fun parse(html: String): List<CatalogSite> =
        ReserveAmericaAvailabilityParser.siteRows(html).mapNotNull { row ->
            val siteId = SITE_ID.find(row)?.groupValues?.get(1) ?: return@mapNotNull null
            val parkId = PARK_ID.find(row)?.groupValues?.get(1) ?: return@mapNotNull null
            val name =
                LABEL_TEXT
                    .find(row)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    .orEmpty()
                    .ifEmpty { siteId }
            CatalogSite(parkId = parkId, siteId = siteId, name = name)
        }

    private val SITE_ID = Regex("""siteId=(\d+)""")
    private val PARK_ID = Regex("""parkId=(\d+)""")
    private val LABEL_TEXT = Regex(""">([^<]+)</a>""")
}
