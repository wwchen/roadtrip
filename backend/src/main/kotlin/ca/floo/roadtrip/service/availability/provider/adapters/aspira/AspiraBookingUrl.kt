package ca.floo.roadtrip.service.availability.provider.adapters.aspira

import ca.floo.roadtrip.clients.aspira.AspiraSearchDefaults
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.ReservationUrlTemplate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The Aspira NextGen (goingtocamp) `create-booking/results` URL scheme — the
 * one place that knows it. Consumed by
 * [AspiraAvailabilityProvider.reservationUrlTemplate] (alerts) and the campsites
 * API (the web app's per-site "Book" link), so the query shape and its inert
 * defaults are never re-spelled at a call site.
 *
 * Aspira's deep link embeds the arrival date in several params (`startDate`,
 * `searchTime`, `flexibleSearch`), so the template is built for a fixed sentinel
 * window and the sentinel dates are then swapped for the
 * [ReservationUrlTemplate] placeholders — the reliable way to templatize a URL whose
 * date appears inside larger strings.
 */
internal object AspiraBookingUrl {
    private val TEMPLATE_START_DATE: LocalDate = LocalDate.parse("2001-01-02")
    private val TEMPLATE_END_DATE: LocalDate = LocalDate.parse("2001-01-03")

    /**
     * Booking-page template for a campsite, or null when neither the
     * campsite's own [reservableProviderRef] nor its [parentRef] carries the
     * transaction-location and map ids the link needs. The site-level ref wins;
     * the campground ref fills what the site omits (e.g. `resourceLocationId`
     * is per-site, `transactionLocationId` is campground-wide).
     */
    fun templateFor(
        host: String,
        reservableProviderRef: JsonElement?,
        parentRef: ProviderRef?,
    ): String? {
        val parent = parentRef as? ProviderRef.Aspira
        val transactionLocationId =
            reservableProviderRef.aspiraLong("transactionLocationId")
                ?: parent?.transactionLocationId
                ?: return null
        val mapId =
            reservableProviderRef.aspiraLong("mapId")
                ?: parent?.mapId
                ?: return null
        val resourceLocationId =
            reservableProviderRef.aspiraLong("resourceLocationId")
                ?: parent?.resourceLocationId
        return template(host, transactionLocationId, mapId, resourceLocationId)
    }

    /** The raw template for explicit ids (sentinel window swapped for placeholders). */
    fun template(
        host: String,
        transactionLocationId: Long,
        mapId: Long,
        resourceLocationId: Long?,
    ): String =
        url(host, transactionLocationId, mapId, resourceLocationId, TEMPLATE_START_DATE, TEMPLATE_END_DATE)
            .replace(TEMPLATE_START_DATE.toString(), ReservationUrlTemplate.START_DATE)
            .replace(TEMPLATE_END_DATE.toString(), ReservationUrlTemplate.END_DATE)
            .replace("nights=1", "nights=${ReservationUrlTemplate.NIGHTS}")

    private fun url(
        host: String,
        transactionLocationId: Long,
        mapId: Long,
        resourceLocationId: Long?,
        startDate: LocalDate,
        endDate: LocalDate,
    ): String {
        val nights = ChronoUnit.DAYS.between(startDate, endDate).toInt()
        val params =
            mutableListOf(
                "transactionLocationId" to transactionLocationId.toString(),
                "mapId" to mapId.toString(),
                "searchTabGroupId" to AspiraSearchDefaults.SEARCH_TAB_GROUP_ID.toString(),
                "bookingCategoryId" to AspiraSearchDefaults.BOOKING_CATEGORY_ID.toString(),
                "startDate" to startDate.toString(),
                "endDate" to endDate.toString(),
                "nights" to nights.toString(),
                "isReserving" to "true",
                "equipmentId" to AspiraSearchDefaults.ANY_EQUIPMENT_CATEGORY_ID.toString(),
                "subEquipmentId" to AspiraSearchDefaults.ANY_SUB_EQUIPMENT_CATEGORY_ID.toString(),
                "peopleCapacityCategoryCounts" to AspiraSearchDefaults.deeplinkPeopleCapacityCategoryCounts(),
                "searchTime" to "${startDate}T00:00:00.000",
                "flexibleSearch" to AspiraSearchDefaults.flexibleSearch(startDate),
                "view" to "grid",
            )
        if (resourceLocationId != null) {
            params += "resourceLocationId" to resourceLocationId.toString()
        }
        return "https://$host/create-booking/results?${queryString(params)}"
    }

    private fun JsonElement?.aspiraLong(key: String): Long? =
        ((this as? JsonObject)?.get(key))?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun queryString(params: List<Pair<String, String>>): String =
        params.joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
