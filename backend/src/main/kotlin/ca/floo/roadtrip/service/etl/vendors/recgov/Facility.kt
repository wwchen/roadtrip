package ca.floo.roadtrip.service.etl.vendors.recgov

import kotlinx.serialization.Serializable

@Suppress("ConstructorParameterNaming", "PropertyName")
@Serializable
data class Facility(
    val FacilityID: Long,
    val FacilityName: String? = null,
    val FacilityLatitude: Double? = null,
    val FacilityLongitude: Double? = null,
    val FacilityPhone: String? = null,
    val FacilityReservationURL: String? = null,
    val FACILITYADDRESS: List<FacilityAddress>? = null,
)
