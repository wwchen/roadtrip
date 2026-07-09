package ca.floo.roadtrip.models.domain

/**
 * Provider-specific parent reservation reference parsed from catalog JSON.
 *
 * This stays in the model layer because reservation adapters, availability
 * services, and API helpers all need the typed shape while persistence only
 * stores the original JSON payload.
 */
sealed interface ProviderRef {
    data class RecGov(
        val recgovId: String,
    ) : ProviderRef

    data class Aspira(
        val transactionLocationId: Long,
        val mapId: Long,
        val resourceLocationId: Long? = null,
    ) : ProviderRef

    data class ReserveAmerica(
        val contractCode: String?,
        val parkId: String,
    ) : ProviderRef

    data class ReserveCalifornia(
        val placeId: Long,
        val facilityIds: List<Long>,
    ) : ProviderRef
}
