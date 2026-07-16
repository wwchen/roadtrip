package ca.floo.roadtrip.service.availability.provider

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/**
 * The rec.gov booking URL scheme, shared by availability adapters, campground CTAs, and ATC payloads.
 *
 * Campflare can report rec.gov-backed campsites, so this lives in provider shared code rather than
 * inside the rec.gov availability adapter package.
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

    fun templateFromUrl(url: String?): String? = campsiteIdFromUrl(url)?.let(::template)

    fun campsite(
        vendorId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): String = ReservationUrlTemplate.fill(template(vendorId), startDate, endDate)

    private fun campsiteIdFromUrl(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val path = runCatching { URI(trimmed).path }.getOrNull() ?: trimmed
        return CAMPSITE_PATH.find(path)?.groupValues?.get(1)
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private val CAMPSITE_PATH = Regex("""(?:^|/)campsites/([^/?#]+)""")
}
