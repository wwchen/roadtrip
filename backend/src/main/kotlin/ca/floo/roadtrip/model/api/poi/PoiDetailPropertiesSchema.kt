package ca.floo.roadtrip.model.api.poi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PoiDetailPropertiesSchema(
    val source: String,
    @SerialName("source_id") val sourceId: String,
    val category: String,
    val subcategory: String? = null,
    val agency: String? = null,
    val name: String,
    val region: String? = null,
    val country: String? = null,
    val detail: PoiCategoryDetailSchema,
)
