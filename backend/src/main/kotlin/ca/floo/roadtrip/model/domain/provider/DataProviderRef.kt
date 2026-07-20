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
        val tenant: String,
        val resourceLocationId: Long,
    ) : DataProviderRef {
        override val provider = DataProvider.ASPIRA

        override fun serialize() = "$tenant:$resourceLocationId"
    }

    data class BcParks(
        val transactionLocationId: Long,
        val mapId: Long,
    ) : DataProviderRef {
        override val provider = DataProvider.STRAPI

        override fun serialize() = "$transactionLocationId:$mapId"
    }

    data class BcParksCampsite(
        val tenant: String = "bc",
        val resourceLocationId: Long,
    ) : DataProviderRef {
        override val provider = DataProvider.STRAPI

        override fun serialize() = "$tenant:$resourceLocationId"
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
                    val first = parts[0].toLongOrNull()
                    if (first != null) {
                        val mapId = parts[1].toLongOrNull() ?: return null
                        Aspira(transactionLocationId = first, mapId = mapId)
                    } else {
                        val resLocId = parts[1].toLongOrNull() ?: return null
                        AspiraCampsite(tenant = parts[0], resourceLocationId = resLocId)
                    }
                }
                1 -> {
                    val resLocId = parts[0].toLongOrNull() ?: return null
                    AspiraCampsite(tenant = "", resourceLocationId = resLocId)
                }
                else -> null
            }
        }

        private fun parseBcParks(ref: String): DataProviderRef? {
            val parts = ref.split(":")
            return when (parts.size) {
                2 -> {
                    val first = parts[0].toLongOrNull()
                    if (first != null) {
                        val mapId = parts[1].toLongOrNull() ?: return null
                        BcParks(transactionLocationId = first, mapId = mapId)
                    } else {
                        val resLocId = parts[1].toLongOrNull() ?: return null
                        BcParksCampsite(tenant = parts[0], resourceLocationId = resLocId)
                    }
                }
                1 -> {
                    val resLocId = parts[0].toLongOrNull() ?: return null
                    BcParksCampsite(tenant = "", resourceLocationId = resLocId)
                }
                else -> null
            }
        }
    }
}
