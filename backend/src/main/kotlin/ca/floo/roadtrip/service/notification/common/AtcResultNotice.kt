package ca.floo.roadtrip.service.notification.common

import kotlinx.serialization.json.JsonObject

/**
 * One backend-owned ATC outcome, handed to [NotificationSender.sendAtcResult]
 * for a transport to render.
 *
 * [bookingSystem] and [cartUrl] come from the booking adapter that made — or
 * would have made — the hold, so the renderers name the vendor and link its
 * cart without knowing any vendor themselves. Both are null when the fire
 * reached no adapter at all, and the copy stays neutral there
 * ([NeutralBookingCopy]). [vendor] is the provider's slug, which the cards
 * still show as a machine-readable label beside the human one.
 *
 * [error] and [detail] carry the failure reason as their own fields rather than
 * buried in [response]: a preflight failure — a dead session, an unreachable
 * companion — produces no companion response at all, and those are exactly the
 * failures the owner can act on. Renderers must prefer them over anything they
 * can dig out of [response].
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
