package ca.floo.roadtrip.model.booking

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import kotlinx.serialization.json.JsonObject

/**
 * Who has to act on a failed booking action — the caller, nobody, or us.
 *
 * The adapter is the only layer that knows what a vendor's codes mean, so it is
 * the layer that answers this. Before it existed the route classified raw
 * companion codes itself against two hand-kept sets, which it had to import
 * `RecGovSessionCodes` to build; a code in neither (`recgov_not_authenticated`)
 * fell through to 502 and claimed an upstream had broken when the caller simply
 * had to sign in.
 *
 * The vendor's own code still travels beside this in [AddToCartResult.Failed.error]:
 * the category decides the *status*, the code decides the *sentence* the user reads.
 */
enum class BookingFailureCategory {
    /** The caller can fix it: sign in, save credentials, pick other dates. */
    CALLER_ACTION,

    /** Nothing is broken and nobody need act; the same request may work shortly. */
    RETRY_LATER,

    /** The booking service or the vendor failed. Never the caller's to fix. */
    UPSTREAM,
}

sealed interface AddToCartResult {
    data class Completed(
        val providerId: BookingProvider,
        val request: JsonObject,
        val response: JsonObject,
    ) : AddToCartResult

    data class Failed(
        val providerId: BookingProvider,
        val error: String,
        val detail: String?,
        val category: BookingFailureCategory,
        val request: JsonObject,
        val response: JsonObject?,
    ) : AddToCartResult

    data object Unsupported : AddToCartResult
}
