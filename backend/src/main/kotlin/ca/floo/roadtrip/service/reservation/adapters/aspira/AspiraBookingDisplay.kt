package ca.floo.roadtrip.service.reservation.adapters.aspira

internal object AspiraBookingDisplay {
    const val DEFAULT_BOOKING_SYSTEM_LABEL = "Aspira NextGen"

    fun bookingSystemLabel(host: String?): String {
        if (host == null) return DEFAULT_BOOKING_SYSTEM_LABEL
        return AspiraTenants.byHost(host)?.bookingSystemLabel
            ?: when {
                host.endsWith("reservation.pc.gc.ca") || host.endsWith("pc.gc.ca") ->
                    "Aspira NextGen (Parks Canada)"
                else -> DEFAULT_BOOKING_SYSTEM_LABEL
            }
    }

    fun ctaLabel(host: String): String =
        AspiraTenants.byHost(host)?.ctaLabel
            ?: when {
                host.endsWith("reservation.pc.gc.ca") || host.endsWith("pc.gc.ca") ->
                    "Reserve on parks.canada.ca"
                else -> "Reserve on $host"
            }
}
