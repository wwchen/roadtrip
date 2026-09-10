package ca.floo.roadtrip.model.api.poi

import ca.floo.roadtrip.model.domain.CampgroundAlert
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One campground closure/notice as the API serves it. */
@Serializable
data class AlertDto(
    val title: String? = null,
    val body: String,
    @SerialName("ends_on") val endsOn: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
) {
    companion object {
        fun from(alert: CampgroundAlert): AlertDto =
            AlertDto(
                title = alert.title,
                body = alert.body,
                endsOn = alert.endsOn,
                sourceUrl = alert.sourceUrl,
            )
    }
}
