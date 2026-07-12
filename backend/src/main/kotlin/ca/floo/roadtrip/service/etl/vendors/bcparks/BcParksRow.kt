package ca.floo.roadtrip.service.etl.vendors.bcparks

import kotlinx.serialization.Serializable

@Serializable
data class BcParksRow(
    val orcs: Long? = null,
    val protectedAreaName: String? = null,
    val type: String? = null,
    @kotlinx.serialization.SerialName("class") val parkClass: String? = null,
    val totalArea: Double? = null,
    val legalStatus: String? = null,
    val url: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val parkContact: String? = null,
    val description: String? = null,
    val parkPhotos: List<BcParksPhoto> = emptyList(),
)
