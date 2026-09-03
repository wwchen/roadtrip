package ca.floo.roadtrip.model.domain

import kotlinx.serialization.Serializable

/** Postal address projection used by canonical non-campsite catalog ETLs. */
@Serializable
data class Address(
    val street: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postcode: String? = null,
    val country: String? = null,
)
