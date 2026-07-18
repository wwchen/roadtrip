package ca.floo.roadtrip.service.availability.provider

object AspiraTenants {
    /** Aspira NextGen typical horizon. */
    private const val DEFAULT_HORIZON_DAYS: Int = 365

    /**
     * The tenant table. Order does not matter; lookup is by host.
     *
     * Adding a tenant: append a row. Validation at boot
     * ([ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry])
     * ensures every host the YAML
     * registry declares has a row here, so a forgotten entry fails
     * loudly instead of silently routing to a missing adapter.
     */
    private val all: List<AspiraTenant> =
        listOf(
            AspiraTenant(
                host = "reservation.pc.gc.ca",
                vendorCode = "aspira_pc",
                bookingHorizonDays = DEFAULT_HORIZON_DAYS,
                bookingSystemLabel = "Aspira NextGen (Parks Canada)",
                ctaLabel = "Reserve on parks.canada.ca",
            ),
            AspiraTenant(
                host = "camping.bcparks.ca",
                vendorCode = "aspira_bc",
                bookingHorizonDays = DEFAULT_HORIZON_DAYS,
                bookingSystemLabel = "Aspira NextGen (BC Parks)",
                ctaLabel = "Book on BC Parks",
            ),
            AspiraTenant(
                host = "washington.goingtocamp.com",
                vendorCode = "aspira_wa",
                bookingHorizonDays = DEFAULT_HORIZON_DAYS,
                bookingSystemLabel = "Aspira NextGen (WA State Parks)",
                ctaLabel = "Book WA State Park",
            ),
        )

    private val byHost: Map<String, AspiraTenant> = all.associateBy { it.host }
    private val byVendorCode: Map<String, AspiraTenant> = all.associateBy { it.vendorCode }

    fun byHost(host: String): AspiraTenant? = byHost[host]

    /** Tenant for a campsite vendor code stamped under that tenant (e.g. `aspira_wa`). */
    fun byVendorCode(vendorCode: String): AspiraTenant? = byVendorCode[vendorCode]

    fun knownHosts(): Set<String> = byHost.keys
}
