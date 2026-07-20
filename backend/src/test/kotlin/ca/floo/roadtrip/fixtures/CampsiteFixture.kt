package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.DEFAULT_CAMPSITE_KIND
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

fun campsiteFixture(
    id: Long = DEFAULT_CAMPSITE_ID,
    campgroundId: Long = DEFAULT_CAMPGROUND_ID,
    vendor: String = DataProvider.RECGOV.id,
    vendorId: String = id.toString(),
    dataProviderRef: DataProviderRef = dataProviderRef(vendor, vendorId),
    bookingProvider: String? = BookingProvider.fromIdOrNull(vendor)?.id,
    bookingProviderRef: String? = vendorId,
    name: String = "Site $id",
    kind: String? = DEFAULT_CAMPSITE_KIND,
    loopName: String? = null,
    reservationUrl: String? = null,
    sourcePayload: JsonElement? = JsonObject(emptyMap()),
): Campsite =
    Campsite(
        id = id,
        campgroundId = campgroundId,
        name = name,
        kind = kind ?: DEFAULT_CAMPSITE_KIND,
        loopName = loopName,
        latitude = null,
        longitude = null,
        reservationUrl = reservationUrl,
        equipment = null,
        kindListed = null,
        schedule = JsonObject(emptyMap()),
        price = JsonObject(emptyMap()),
        firepit = null,
        picnicTable = null,
        adaAccessible = null,
        waterHookups = null,
        electricHookups = null,
        sewerHookups = null,
        maxPeople = null,
        maxCars = null,
        pullThrough = null,
        drivewayLength = null,
        maxRvLength = null,
        maxTrailerLength = null,
        photos = JsonArray(emptyList()),
        sourcePayload = sourcePayload ?: JsonObject(emptyMap()),
        createdAt = defaultInstant,
        updatedAt = defaultInstant,
        deletedAt = null,
        dataProvider = dataProviderRef.provider.id,
        dataProviderRefValue = dataProviderRef.serialize(),
        bookingProvider = bookingProvider,
        bookingProviderRef = bookingProviderRef,
    )

private fun dataProviderRef(
    vendor: String,
    vendorId: String,
): DataProviderRef {
    val provider = DataProvider.fromId(vendor)
    return DataProviderRef.parse(provider, vendorId)
        ?: error("Invalid test data provider ref: provider=$vendor ref=$vendorId")
}

private const val DEFAULT_CAMPSITE_ID = 1L
private const val DEFAULT_CAMPGROUND_ID = 1L
private val defaultInstant: Instant = Instant.parse("2026-01-01T00:00:00Z")
