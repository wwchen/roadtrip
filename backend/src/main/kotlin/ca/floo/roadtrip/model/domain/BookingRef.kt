package ca.floo.roadtrip.model.domain

/**
 * Typed, colon-delimited booking reference that encodes all IDs the
 * availability adapter needs to route a request. Each variant's field
 * order is fixed so serialization is deterministic.
 *
 * Parse with [BookingRef.parse]; serialize with [serialize].
 */
sealed interface BookingRef {
    val provider: BookingProvider

    fun serialize(): String

    data class Aspira(
        val tenant: String,
        val transactionLocationId: Long,
        val mapId: Long,
        val resourceLocationId: Long,
    ) : BookingRef {
        override val provider: BookingProvider = BookingProvider.ASPIRA

        override fun serialize(): String = "$tenant:$transactionLocationId:$mapId:$resourceLocationId"
    }

    data class RecGov(
        val facilityId: String,
    ) : BookingRef {
        override val provider: BookingProvider = BookingProvider.RECGOV

        override fun serialize(): String = facilityId
    }

    data class Campflare(
        val campgroundId: String,
    ) : BookingRef {
        override val provider: BookingProvider = BookingProvider.CAMPFLARE

        override fun serialize(): String = campgroundId
    }

    data class ReserveAmerica(
        val contractCode: String,
        val parkId: String,
    ) : BookingRef {
        override val provider: BookingProvider = BookingProvider.RESERVEAMERICA

        override fun serialize(): String = "$contractCode:$parkId"
    }

    data class ReserveCalifornia(
        val placeId: Long,
        val facilityIds: List<Long>,
    ) : BookingRef {
        override val provider: BookingProvider = BookingProvider.RESERVECALIFORNIA

        override fun serialize(): String = "$placeId:${facilityIds.joinToString(",")}"
    }

    fun toProviderRef(): ProviderRef =
        when (this) {
            is Aspira ->
                ProviderRef.Aspira(
                    transactionLocationId = transactionLocationId,
                    mapId = mapId,
                    resourceLocationId = resourceLocationId,
                )
            is RecGov -> ProviderRef.RecGov(recgovId = facilityId)
            is Campflare -> ProviderRef.Campflare(campgroundId = campgroundId)
            is ReserveAmerica -> ProviderRef.ReserveAmerica(contractCode = contractCode, parkId = parkId)
            is ReserveCalifornia -> ProviderRef.ReserveCalifornia(placeId = placeId, facilityIds = facilityIds)
        }

    companion object {
        fun parse(
            provider: BookingProvider,
            ref: String,
        ): BookingRef? =
            when (provider) {
                BookingProvider.ASPIRA -> parseAspira(ref)
                BookingProvider.RECGOV -> RecGov(facilityId = ref)
                BookingProvider.CAMPFLARE -> Campflare(campgroundId = ref)
                BookingProvider.RESERVEAMERICA -> parseReserveAmerica(ref)
                BookingProvider.RESERVECALIFORNIA -> parseReserveCalifornia(ref)
            }

        private fun parseAspira(ref: String): Aspira? {
            val parts = ref.split(":")
            if (parts.size != 4) return null
            val tenant = parts[0]
            val transactionLocationId = parts[1].toLongOrNull() ?: return null
            val mapId = parts[2].toLongOrNull() ?: return null
            val resourceLocationId = parts[3].toLongOrNull() ?: return null
            return Aspira(
                tenant = tenant,
                transactionLocationId = transactionLocationId,
                mapId = mapId,
                resourceLocationId = resourceLocationId,
            )
        }

        private fun parseReserveAmerica(ref: String): ReserveAmerica? {
            val idx = ref.indexOf(':')
            if (idx < 0) return null
            return ReserveAmerica(
                contractCode = ref.substring(0, idx),
                parkId = ref.substring(idx + 1),
            )
        }

        private fun parseReserveCalifornia(ref: String): ReserveCalifornia? {
            val idx = ref.indexOf(':')
            if (idx < 0) return null
            val placeId = ref.substring(0, idx).toLongOrNull() ?: return null
            val facilityIds = ref.substring(idx + 1).split(",").mapNotNull { it.toLongOrNull() }
            if (facilityIds.isEmpty()) return null
            return ReserveCalifornia(placeId = placeId, facilityIds = facilityIds)
        }
    }
}
