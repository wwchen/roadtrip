package ca.floo.roadtrip.service.etl.vendors.recgov

import kotlinx.serialization.Serializable

@Suppress("ConstructorParameterNaming", "PropertyName")
@Serializable
data class FacilityAddress(
    val FacilityStreetAddress1: String? = null,
    val City: String? = null,
    val AddressStateCode: String? = null,
    val PostalCode: String? = null,
    val AddressCountryCode: String? = null,
)
