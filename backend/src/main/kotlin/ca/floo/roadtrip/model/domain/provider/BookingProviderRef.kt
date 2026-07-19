package ca.floo.roadtrip.model.domain.provider

/**
 * Typed, colon-delimited booking reference that encodes all IDs the
 * availability adapter needs to route a request. Each variant's field
 * order is fixed so serialization is deterministic.
 *
 * Parse with [BookingProviderRef.parse]; serialize with [serialize].
 */
sealed interface BookingProviderRef {
    val provider: BookingProvider

    fun serialize(): String

    data class Aspira(
        val tenant: String?,
        val transactionLocationId: Long,
        val mapId: Long,
        val resourceLocationId: Long?,
    ) : BookingProviderRef {
        override val provider: BookingProvider = BookingProvider.ASPIRA

        override fun serialize(): String {
            val parts =
                listOfNotNull(
                    tenant ?: "unknown",
                    transactionLocationId.toString(),
                    mapId.toString(),
                    resourceLocationId?.toString() ?: "null",
                )
            return parts.joinToString(":")
        }
    }

    data class RecGov(
        val facilityId: String,
    ) : BookingProviderRef {
        override val provider: BookingProvider = BookingProvider.RECGOV

        override fun serialize(): String = facilityId
    }

    data class Campflare(
        val campgroundId: String,
    ) : BookingProviderRef {
        override val provider: BookingProvider = BookingProvider.CAMPFLARE

        override fun serialize(): String = campgroundId
    }

    data class ReserveAmerica(
        val contractCode: String?,
        val parkId: String,
    ) : BookingProviderRef {
        override val provider: BookingProvider = BookingProvider.RESERVEAMERICA

        override fun serialize(): String = "${contractCode ?: "null"}:$parkId"
    }

    data class ReserveCalifornia(
        val placeId: Long,
        val facilityIds: List<Long>,
    ) : BookingProviderRef {
        override val provider: BookingProvider = BookingProvider.RESERVECALIFORNIA

        override fun serialize(): String = "$placeId:${facilityIds.joinToString(",")}"
    }

    companion object {
        fun parse(
            provider: BookingProvider,
            ref: String,
        ): BookingProviderRef? =
            when (provider) {
                BookingProvider.ASPIRA -> parseAspira(ref)
                BookingProvider.RECGOV -> RecGov(facilityId = ref)
                BookingProvider.CAMPFLARE -> Campflare(campgroundId = ref)
                BookingProvider.RESERVEAMERICA -> parseReserveAmerica(ref)
                BookingProvider.RESERVECALIFORNIA -> parseReserveCalifornia(ref)
            }

        private fun parseAspira(ref: String): Aspira? {
            val parts = ref.split(":")
            if (parts.size < 3) return null
            val tenant = parts[0].takeIf { it != "unknown" && it != "null" }
            val transactionLocationId = parts[1].toLongOrNull() ?: return null
            val mapId = parts[2].toLongOrNull() ?: return null
            val resourceLocationId = parts.getOrNull(3)?.takeIf { it != "null" }?.toLongOrNull()
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
            val contractCode = ref.substring(0, idx).takeIf { it != "null" }
            return ReserveAmerica(
                contractCode = contractCode,
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
