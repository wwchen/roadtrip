package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a bulk availability read answers with for each POI. */
@Serializable
enum class BulkAvailabilityView {
    /** Every campsite's own day cells — the scan the endpoint was built for. */
    @SerialName("campsites")
    CAMPSITES,

    /** One [BulkPoiSummaryDto] per POI: how the stay looks there, without the cells. */
    @SerialName("summary")
    SUMMARY,
}
