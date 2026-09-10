package ca.floo.roadtrip.model.domain

import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * One row in the `campsites` table. Never serialized: the wire shape is
 * [ca.floo.roadtrip.model.api.CampsiteDto].
 */
data class Campsite(
    val id: Long,
    val campgroundId: Long,
    val name: String,
    val kind: CampsiteKind,
    val loopName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val reservationUrl: String?,
    val equipment: List<String>,
    val kindListed: String?,
    val schedule: JsonElement,
    val price: JsonElement,
    val firepit: Boolean?,
    val picnicTable: Boolean?,
    val adaAccessible: Boolean?,
    val waterHookups: Boolean?,
    val electricHookups: Boolean?,
    val sewerHookups: Boolean?,
    val maxPeople: Int?,
    val maxCars: Int?,
    val pullThrough: Boolean?,
    val drivewayLength: Int?,
    val maxRvLength: Int?,
    val maxTrailerLength: Double?,
    val photos: List<CatalogPhoto>,
    val attributes: List<CampsiteAttribute>,
    val description: String?,
    val minPeople: Int?,
    val sourcePayload: JsonElement,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val dataProvider: String,
    val dataProviderRefValue: String,
    val bookingProvider: String?,
    val bookingProviderRef: String?,
) {
    val dataProviderRef: DataProviderRef = parseDataProviderRef(dataProvider, dataProviderRefValue)
}

private fun parseDataProviderRef(
    dataProvider: String,
    dataProviderRef: String,
): DataProviderRef =
    DataProviderRef.parse(DataProvider.fromId(dataProvider), dataProviderRef)
        ?: error("Failed to parse DataProviderRef for provider=$dataProvider ref=$dataProviderRef")
