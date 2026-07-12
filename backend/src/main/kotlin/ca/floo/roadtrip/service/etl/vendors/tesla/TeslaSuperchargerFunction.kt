package ca.floo.roadtrip.service.etl.vendors.tesla

import kotlinx.serialization.Serializable

@Serializable
data class TeslaSuperchargerFunction(
    @kotlinx.serialization.SerialName("show_on_find_us") val showOnFindUs: String? = null,
    @kotlinx.serialization.SerialName("site_status") val siteStatus: String? = null,
)
