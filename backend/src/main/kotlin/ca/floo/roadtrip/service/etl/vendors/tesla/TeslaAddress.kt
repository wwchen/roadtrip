package ca.floo.roadtrip.service.etl.vendors.tesla

import kotlinx.serialization.Serializable

@Serializable
data class TeslaAddress(
    val street: String? = null,
    val streetNumber: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val countryCode: String? = null,
)
