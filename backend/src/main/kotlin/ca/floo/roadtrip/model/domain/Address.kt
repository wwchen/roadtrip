package ca.floo.roadtrip.model.domain

import kotlinx.serialization.Serializable

/**
 * Postal address projection used by canonical non-campsite catalog ETLs.
 *
 * [full] is the vendor's own one-line rendering. The drawer prefers it over
 * the composed parts, so it is carried rather than recomposed.
 */
@Serializable
data class Address(
    val street: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postcode: String? = null,
    val country: String? = null,
    val full: String? = null,
)
