package ca.floo.roadtrip.service.availability.provider.adapters.recgov

import ca.floo.roadtrip.service.availability.provider.ReservationUrlTemplate
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/**
 * The rec.gov single-site booking URL scheme — the one place that knows it.
 * Consumed by [RecGovAvailabilityProvider.reservationUrlTemplate] (alerts) and the
 * campsites API (the web app's per-site "Book" link), so the scheme is never
 * re-spelled at a call site.
 */
internal object RecGovBookingUrl {
    private const val CAMPGROUND_URL = "https://www.recreation.gov/camping/campgrounds"
    private const val CAMPSITE_URL = "https://www.recreation.gov/camping/campsites"

    fun campground(recgovId: String): String = "$CAMPGROUND_URL/${urlEncode(recgovId)}"

    fun campgroundTemplate(recgovId: String): String =
        "${campground(recgovId)}?startDate=${ReservationUrlTemplate.START_DATE}&endDate=${ReservationUrlTemplate.END_DATE}"

    fun campground(
        recgovId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): String = ReservationUrlTemplate.fill(campgroundTemplate(recgovId), startDate, endDate)

    /** Booking-page template for the campsite [vendorId], with window placeholders. */
    fun template(vendorId: String): String =
        "$CAMPSITE_URL/${urlEncode(vendorId)}?startDate=${ReservationUrlTemplate.START_DATE}&endDate=${ReservationUrlTemplate.END_DATE}"

    fun campsite(
        vendorId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): String = ReservationUrlTemplate.fill(template(vendorId), startDate, endDate)

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
