package ca.floo.roadtrip.service.booking

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate

data class ReservationStay(
    val start: LocalDate,
    val nights: Int,
) {
    val end: LocalDate = start.plusDays(nights.toLong())
}

fun recgovCampsiteUrl(
    campsiteId: String,
    stay: ReservationStay? = null,
): String =
    buildUrl(
        base = "https://www.recreation.gov/camping/campsites/${urlEncode(campsiteId)}",
        params =
            if (stay == null) {
                emptyList()
            } else {
                listOf(
                    "startDate" to stay.start.toString(),
                    "endDate" to stay.end.toString(),
                )
            },
    )

fun aspiraReservableUrl(
    host: String,
    transactionLocationId: Long,
    mapId: Long,
    resourceLocationId: Long?,
    stay: ReservationStay? = null,
): String {
    val params =
        mutableListOf(
            "transactionLocationId" to transactionLocationId.toString(),
            "mapId" to mapId.toString(),
        )
    if (stay != null) {
        params +=
            listOf(
                "searchTabGroupId" to "0",
                "bookingCategoryId" to "0",
                "startDate" to stay.start.toString(),
                "endDate" to stay.end.toString(),
                "nights" to stay.nights.toString(),
                "isReserving" to "true",
                "equipmentId" to "-32768",
                "subEquipmentId" to "-32768",
                "peopleCapacityCategoryCounts" to "[[-32767,null,1,null]]",
                "searchTime" to "${stay.start}T00:00:00.000",
                "flexibleSearch" to """[false,false,"${stay.start}",${stay.nights}]""",
                "view" to "list",
                "filterData" to """{"-32756":"[[1],0,0,0]"}""",
            )
    }
    if (resourceLocationId != null) {
        params += "resourceLocationId" to resourceLocationId.toString()
    }
    return buildUrl("https://$host/create-booking/results", params)
}

fun aspiraHostForVendor(vendor: String): String? =
    when (vendor) {
        "aspira_pc" -> "reservation.pc.gc.ca"
        "aspira_bc" -> "camping.bcparks.ca"
        "aspira_wa" -> "washington.goingtocamp.com"
        else -> null
    }

private fun buildUrl(
    base: String,
    params: List<Pair<String, String>>,
): String =
    if (params.isEmpty()) {
        base
    } else {
        "$base?${queryString(params)}"
    }

private fun queryString(params: List<Pair<String, String>>): String =
    params.joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }

private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
