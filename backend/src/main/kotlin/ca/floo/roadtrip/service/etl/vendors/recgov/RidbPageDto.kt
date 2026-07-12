package ca.floo.roadtrip.service.etl.vendors.recgov

import kotlinx.serialization.Serializable

// RIDB page envelope: { METADATA: {...}, RECDATA: [Facility, ...] }.
// Field names match RIDB's PascalCase verbatim — kotlinx-serialization
// would normally complain, but the fields aren't generic enough to
// alias, so we suppress and live with the naming noise.
@Suppress("ConstructorParameterNaming", "PropertyName")
@Serializable
data class RidbPageDto(
    val RECDATA: List<Facility> = emptyList(),
)
