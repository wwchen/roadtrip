package ca.floo.roadtrip.models.booking

sealed interface AddToCartResult {
    data class Queued(
        val dispatchId: Long,
        val providerId: BookingProviderId,
        val notifiedWaiters: Int,
    ) : AddToCartResult

    data object Unsupported : AddToCartResult
}
