package ca.floo.roadtrip.service.availability.provider

/** Temporary copy of the per-tenant labels; Task 4 moves these callers onto TenantRegistry. */
private val labelsByHost: Map<String, Pair<String, String>> =
    mapOf(
        "reservation.pc.gc.ca" to ("Aspira NextGen (Parks Canada)" to "Reserve on parks.canada.ca"),
        "camping.bcparks.ca" to ("Aspira NextGen (BC Parks)" to "Book on BC Parks"),
        "washington.goingtocamp.com" to ("Aspira NextGen (WA State Parks)" to "Book WA State Park"),
    )

internal object AspiraBookingDisplay {
    const val DEFAULT_BOOKING_SYSTEM_LABEL = "Aspira NextGen"

    fun bookingSystemLabel(host: String?): String {
        if (host == null) return DEFAULT_BOOKING_SYSTEM_LABEL
        return labelsByHost[host]?.first
            ?: when {
                host.endsWith("reservation.pc.gc.ca") || host.endsWith("pc.gc.ca") ->
                    "Aspira NextGen (Parks Canada)"
                else -> DEFAULT_BOOKING_SYSTEM_LABEL
            }
    }

    fun ctaLabel(host: String): String =
        labelsByHost[host]?.second
            ?: when {
                host.endsWith("reservation.pc.gc.ca") || host.endsWith("pc.gc.ca") ->
                    "Reserve on parks.canada.ca"
                else -> "Reserve on $host"
            }
}
