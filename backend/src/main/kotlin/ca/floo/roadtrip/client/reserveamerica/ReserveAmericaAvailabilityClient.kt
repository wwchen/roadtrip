package ca.floo.roadtrip.client.reserveamerica

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate

fun interface ReserveAmericaAvailabilityClient : AutoCloseable {
    suspend fun fetch(
        host: String,
        contractCode: String,
        parkId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): ReserveAmericaAvailability

    override fun close() {}
}

internal fun queryString(vararg params: Pair<String, String>): String =
    params.joinToString("&") { (key, value) -> "${key.urlEncode()}=${value.urlEncode()}" }

internal fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)
