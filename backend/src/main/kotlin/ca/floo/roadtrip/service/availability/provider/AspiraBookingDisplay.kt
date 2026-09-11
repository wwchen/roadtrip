package ca.floo.roadtrip.service.availability.provider

/** File-private: dies with [labelsByHost] in Task 4. */
private data class TenantLabels(
    val bookingSystem: String,
    val cta: String,
)

/** Temporary copy of the per-tenant labels; Task 4 moves these callers onto TenantRegistry. */
private val labelsByHost: Map<String, TenantLabels> =
    mapOf(
        "reservation.pc.gc.ca" to TenantLabels("Aspira NextGen (Parks Canada)", "Reserve on parks.canada.ca"),
        "camping.bcparks.ca" to TenantLabels("Aspira NextGen (BC Parks)", "Book on BC Parks"),
        "washington.goingtocamp.com" to TenantLabels("Aspira NextGen (WA State Parks)", "Book WA State Park"),
    )

internal object AspiraBookingDisplay {
    const val DEFAULT_BOOKING_SYSTEM_LABEL = "Aspira NextGen"

    fun bookingSystemLabel(host: String?): String {
        if (host == null) return DEFAULT_BOOKING_SYSTEM_LABEL
        return labelsByHost[host]?.bookingSystem
            ?: when {
                host.endsWith("reservation.pc.gc.ca") || host.endsWith("pc.gc.ca") ->
                    "Aspira NextGen (Parks Canada)"
                else -> DEFAULT_BOOKING_SYSTEM_LABEL
            }
    }

    fun ctaLabel(host: String): String =
        labelsByHost[host]?.cta
            ?: when {
                host.endsWith("reservation.pc.gc.ca") || host.endsWith("pc.gc.ca") ->
                    "Reserve on parks.canada.ca"
                else -> "Reserve on $host"
            }
}
