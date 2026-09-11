package ca.floo.roadtrip.service.etl.vendors.aspira

/** One BC Parks Strapi protected-area row, as [BcParksStrapiSource] parses it. */
data class BcParksStrapiRow(
    val name: String,
    val lat: Double,
    val lon: Double,
    val orcs: Long?,
    val url: String?,
    val description: String?,
    val phone: String?,
    val photoUrl: String?,
)
