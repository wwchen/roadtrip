package ca.floo.roadtrip.service.notification.common

import kotlinx.serialization.json.JsonObject

/**
 * One backend-owned ATC outcome for [NotificationSender.sendAtcResult] to render.
 * [bookingSystem]/[cartUrl] name and link the adapter's own hold (null and
 * neutral, [NeutralBookingCopy], otherwise); [error]/[detail] outrank [response].
 */
data class AtcResultNotice(
    val watchId: Long,
    val vendor: String,
    val status: String,
    val request: JsonObject,
    val response: JsonObject?,
    val bookingSystem: String? = null,
    val cartUrl: String? = null,
    val error: String? = null,
    val detail: String? = null,
)
