package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.domain.Address
import ca.floo.roadtrip.model.domain.CampgroundContact
import ca.floo.roadtrip.model.domain.CampgroundLink
import ca.floo.roadtrip.model.domain.CampgroundLocation
import ca.floo.roadtrip.model.domain.CampgroundManagement
import ca.floo.roadtrip.model.domain.CampgroundPhoto
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Encodes the campground JSONB column shapes that every vendor ETL emits.
 * Vendors describe the value; this is the one place the wire shape is
 * produced, so a key rename happens in the domain type, not per vendor.
 */
object CampgroundJsonb {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { explicitNulls = false }

    fun location(
        latitude: Double,
        longitude: Double,
        region: String? = null,
        country: String? = null,
        address: Address? = null,
    ): JsonObject = encode(CampgroundLocation(latitude, longitude, region, country, address = address))

    fun links(urls: List<String>): JsonArray = JsonArray(urls.map { encode(CampgroundLink(it)) })

    fun links(url: String): JsonArray = links(listOf(url))

    fun photos(url: String): JsonArray = JsonArray(listOf(encode(CampgroundPhoto(url))))

    fun management(agency: String): JsonObject = encode(CampgroundManagement(agency))

    fun contact(phone: String): JsonObject = encode(CampgroundContact(phone))

    private inline fun <reified T> encode(value: T): JsonObject = json.encodeToJsonElement(value).jsonObject
}
