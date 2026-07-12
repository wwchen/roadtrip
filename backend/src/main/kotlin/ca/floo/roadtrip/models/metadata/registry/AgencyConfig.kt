package ca.floo.roadtrip.models.metadata.registry

sealed interface AgencyConfig {
    data class Constant(
        val value: String,
    ) : AgencyConfig

    data class DerivedFromField(
        val field: String,
    ) : AgencyConfig

    companion object {
        const val DERIVED_FROM_FIELD_KEY = "derived_from_field"
    }
}
