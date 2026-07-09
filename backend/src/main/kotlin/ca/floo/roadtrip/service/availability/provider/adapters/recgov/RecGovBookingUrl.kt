package ca.floo.roadtrip.service.availability.provider.adapters.recgov

import ca.floo.roadtrip.service.availability.provider.BookingUrlTemplate
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The rec.gov single-site booking URL scheme — the one place that knows it.
 * Consumed by [RecGovAvailabilityProvider.bookingUrlTemplate] (alerts) and the
 * reservables API (the web app's per-site "Book" link), so the scheme is never
 * re-spelled at a call site.
 */
internal object RecGovBookingUrl {
    private const val CAMPGROUND_URL = "https://www.recreation.gov/camping/campgrounds"
    private const val CAMPSITE_URL = "https://www.recreation.gov/camping/campsites"

    fun campground(recgovId: String): String = "$CAMPGROUND_URL/${urlEncode(recgovId)}"

    /** Booking-page template for the campsite [vendorId], with window placeholders. */
    fun template(vendorId: String): String =
        "$CAMPSITE_URL/${urlEncode(vendorId)}?startDate=${BookingUrlTemplate.START_DATE}&endDate=${BookingUrlTemplate.END_DATE}"

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
