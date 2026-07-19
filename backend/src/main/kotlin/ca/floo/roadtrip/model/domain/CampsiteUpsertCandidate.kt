package ca.floo.roadtrip.model.domain

import kotlinx.serialization.json.JsonElement

const val DEFAULT_CAMPSITE_KIND = "site"

data class CampsiteUpsertCandidate(
    val dataProvider: DataProvider,
    val dataProviderRef: String,
    val bookingProvider: BookingProvider? = null,
    val bookingProviderRef: String? = null,
    val parentDataProvider: DataProvider?,
    val parentDataProviderRef: String?,
    val name: String,
    val kind: String = DEFAULT_CAMPSITE_KIND,
    val loopName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val reservationUrl: String? = null,
    val equipment: JsonElement? = null,
    val kindListed: String? = null,
    val schedule: JsonElement? = null,
    val price: JsonElement? = null,
    val firepit: Boolean? = null,
    val picnicTable: Boolean? = null,
    val adaAccessible: Boolean? = null,
    val waterHookups: Boolean? = null,
    val electricHookups: Boolean? = null,
    val sewerHookups: Boolean? = null,
    val maxPeople: Int? = null,
    val maxCars: Int? = null,
    val pullThrough: Boolean? = null,
    val drivewayLength: Int? = null,
    val maxRvLength: Int? = null,
    val maxTrailerLength: Double? = null,
    val photos: JsonElement? = null,
    val sourcePayload: JsonElement? = null,
)
