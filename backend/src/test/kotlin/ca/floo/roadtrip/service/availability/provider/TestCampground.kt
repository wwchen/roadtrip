package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import kotlinx.serialization.json.JsonNull
import java.time.Instant

internal fun testCampground(
    bookingProvider: String?,
    bookingProviderRef: String?,
    dataProviderRef: DataProviderRef = DataProviderRef.RecGov(id = "test"),
): Campground =
    Campground(
        id = 1L,
        name = "Test",
        status = null,
        statusDescription = null,
        kind = null,
        shortDescription = null,
        mediumDescription = null,
        longDescription = null,
        location = null,
        defaultCampsiteSchedule = JsonNull,
        amenities = JsonNull,
        maxRvLength = null,
        maxTrailerLength = null,
        hasPullThroughSites = null,
        bigRigFriendly = null,
        reservationUrl = null,
        links = emptyList(),
        photos = emptyList(),
        alerts = JsonNull,
        price = JsonNull,
        cellService = JsonNull,
        management = null,
        contact = null,
        connections = JsonNull,
        metadata = JsonNull,
        sourcePayload = JsonNull,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        deletedAt = null,
        dataProviderRef = dataProviderRef,
        bookingProvider = bookingProvider,
        bookingProviderRef = bookingProviderRef,
    )
