package ca.floo.roadtrip.model.domain.provider

enum class DataProvider(
    val id: String,
) {
    CAMPFLARE("campflare"),
    RECGOV("recgov"),
    ASPIRA("aspira"),
    STRAPI("bcparks-strapi"),
    RESERVEAMERICA("reserveamerica"),
    RESERVECALIFORNIA("reservecalifornia"),
    TESLA_SUPERCHARGER("tesla_supercharger"),
    PLANET_FITNESS_LOCATION("planet_fitness_location"),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }

        fun fromId(id: String): DataProvider = byId[id] ?: throw IllegalArgumentException("Unknown data provider: $id")

        fun fromIdOrNull(id: String): DataProvider? = byId[id]
    }
}
