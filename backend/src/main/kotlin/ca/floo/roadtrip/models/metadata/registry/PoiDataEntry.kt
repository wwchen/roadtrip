package ca.floo.roadtrip.models.metadata.registry

import com.charleskorn.kaml.YamlNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PoiDataEntry(
    val name: String,
    val enabled: Boolean = true,
    val category: String,
    // FE sub-bucket for legend toggles + circle-color (e.g. campground →
    // federal | state | local | provincial). Null when the category has
    // no sub-bucket (planet-fitness, supercharger).
    val subcategory: String? = null,
    @SerialName("agency")
    val agencyNode: YamlNode? = null,
    val etls: List<EtlEntry>,
) {
    val agency: AgencyConfig?
        get() = agencyNode?.toAgencyConfig()
}
