package ca.floo.roadtrip.model.domain.provider

sealed interface DataProviderRef {
    val provider: DataProvider

    fun serialize(): String

    data class RecGov(
        val id: String,
    ) : DataProviderRef {
        override val provider = DataProvider.RECGOV

        override fun serialize() = id
    }

    data class Campflare(
        val id: String,
    ) : DataProviderRef {
        override val provider = DataProvider.CAMPFLARE

        override fun serialize() = id
    }

    data class Aspira(
        val transactionLocationId: Long,
        val mapId: Long,
    ) : DataProviderRef {
        override val provider = DataProvider.ASPIRA

        override fun serialize() = "$transactionLocationId:$mapId"
    }

    data class AspiraCampsite(
        val resourceLocationId: Long,
    ) : DataProviderRef {
        override val provider = DataProvider.ASPIRA

        override fun serialize() = resourceLocationId.toString()
    }

    data class BcParks(
        val transactionLocationId: Long,
        val mapId: Long,
    ) : DataProviderRef {
        override val provider = DataProvider.STRAPI

        override fun serialize() = "$transactionLocationId:$mapId"
    }

    data class BcParksCampsite(
        val resourceLocationId: Long,
    ) : DataProviderRef {
        override val provider = DataProvider.STRAPI

        override fun serialize() = resourceLocationId.toString()
    }

    data class ReserveAmerica(
        val id: String,
    ) : DataProviderRef {
        override val provider = DataProvider.RESERVEAMERICA

        override fun serialize() = id
    }

    data class ReserveCalifornia(
        val id: String,
    ) : DataProviderRef {
        override val provider = DataProvider.RESERVECALIFORNIA

        override fun serialize() = id
    }

    data class TeslaSupercharger(
        val locationId: String,
    ) : DataProviderRef {
        override val provider = DataProvider.TESLA_SUPERCHARGER

        override fun serialize() = locationId
    }

    data class PlanetFitnessLocation(
        val locationId: String,
    ) : DataProviderRef {
        override val provider = DataProvider.PLANET_FITNESS_LOCATION

        override fun serialize() = locationId
    }

    companion object {
        fun parse(
            provider: DataProvider,
            ref: String,
        ): DataProviderRef? =
            when (provider) {
                DataProvider.RECGOV -> RecGov(id = ref)
                DataProvider.CAMPFLARE -> Campflare(id = ref)
                DataProvider.ASPIRA -> parseAspira(ref)
                DataProvider.STRAPI -> parseBcParks(ref)
                DataProvider.RESERVEAMERICA -> ReserveAmerica(id = ref)
                DataProvider.RESERVECALIFORNIA -> ReserveCalifornia(id = ref)
                DataProvider.TESLA_SUPERCHARGER -> TeslaSupercharger(locationId = ref)
                DataProvider.PLANET_FITNESS_LOCATION -> PlanetFitnessLocation(locationId = ref)
            }

        private fun parseAspira(ref: String): DataProviderRef? {
            val parts = ref.split(":")
            return when (parts.size) {
                2 -> {
                    val tLID = parts[0].toLongOrNull() ?: return null
                    val mapId = parts[1].toLongOrNull() ?: return null
                    Aspira(transactionLocationId = tLID, mapId = mapId)
                }
                1 -> {
                    val resLocId = parts[0].toLongOrNull() ?: return null
                    AspiraCampsite(resourceLocationId = resLocId)
                }
                else -> null
            }
        }

        private fun parseBcParks(ref: String): DataProviderRef? {
            val parts = ref.split(":")
            return when (parts.size) {
                2 -> {
                    val tLID = parts[0].toLongOrNull() ?: return null
                    val mapId = parts[1].toLongOrNull() ?: return null
                    BcParks(transactionLocationId = tLID, mapId = mapId)
                }
                1 -> {
                    val resLocId = parts[0].toLongOrNull() ?: return null
                    BcParksCampsite(resourceLocationId = resLocId)
                }
                else -> null
            }
        }
    }
}
