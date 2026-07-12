package ca.floo.roadtrip.service.etl.vendors.aspira

import kotlinx.serialization.Serializable

@Serializable
data class AspiraLeaf(
    val name: String,
    @kotlinx.serialization.SerialName("transaction_location_id") val transactionLocationId: Long,
    @kotlinx.serialization.SerialName("map_id") val mapId: Long,
    @kotlinx.serialization.SerialName("resource_location_id") val resourceLocationId: Long? = null,
    /** Title of the parent map node, when the leaf is a sub-area (PC backcountry). */
    @kotlinx.serialization.SerialName("parent_name") val parentName: String? = null,
)
