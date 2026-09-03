package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.domain.Address
import ca.floo.roadtrip.model.domain.CampgroundContact
import ca.floo.roadtrip.model.domain.CampgroundLink
import ca.floo.roadtrip.model.domain.CampgroundLocation
import ca.floo.roadtrip.model.domain.CampgroundManagement
import ca.floo.roadtrip.model.domain.CampgroundPhoto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

// Campflare's upstream key sets, in the precedence order the read path applied
// before these columns were typed. First present, non-blank key wins.
private val photoUrlKeys = listOf("url", "large_url", "medium_url", "small_url", "original_url")
private val linkUrlKeys = listOf("url", "href")
private val linkTitleKeys = listOf("title", "label", "name")
private val agencyKeys = listOf("agency", "agency_name")
private val websiteKeys = listOf("agency_website", "website_url", "website", "url")
private val phoneKeys = listOf("phone", "primary_phone")
private val emailKeys = listOf("email", "primary_email")
private val streetKeys = listOf("street", "street1", "address_line")
private val stateKeys = listOf("state", "state_code")
private val postcodeKeys = listOf("postcode", "postal_code", "zipcode")
private val countryKeys = listOf("country", "country_code")

internal fun campflareLocation(
    location: JsonObject,
    latitude: Double,
    longitude: Double,
): CampgroundLocation =
    CampgroundLocation(
        latitude = latitude,
        longitude = longitude,
        region = location.stringField("region"),
        country = location.stringField("country"),
        elevation = location.doubleField("elevation"),
        address = location.objectField("address")?.let(::campflareAddress),
    )

private fun campflareAddress(address: JsonObject): Address? {
    val parsed =
        Address(
            street = address.first(streetKeys),
            city = address.stringField("city"),
            state = address.first(stateKeys),
            postcode = address.first(postcodeKeys),
            country = address.first(countryKeys),
        )
    return parsed.takeIf { it != Address() }
}

internal fun campflarePhotos(photos: JsonElement?): List<CampgroundPhoto> =
    photos?.jsonArray.orEmpty().mapNotNull { entry ->
        (entry as? JsonObject)?.first(photoUrlKeys)?.let(::CampgroundPhoto)
    }

/**
 * Upstream links plus the Campflare page itself, which upstream does not always
 * list. Appended only when no existing link already points at it.
 */
internal fun campflareLinks(
    links: JsonElement?,
    sourceUrl: String,
): List<CampgroundLink> {
    val parsed =
        links?.jsonArray.orEmpty().mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val url = obj.first(linkUrlKeys) ?: return@mapNotNull null
            CampgroundLink(url = url, title = obj.first(linkTitleKeys))
        }
    if (parsed.any { it.url == sourceUrl }) return parsed
    return parsed + CampgroundLink(url = sourceUrl, title = CAMPFLARE_SOURCE_LINK_TITLE)
}

internal fun campflareManagement(management: JsonObject?): CampgroundManagement? {
    val agency = management?.first(agencyKeys) ?: return null
    return CampgroundManagement(agency = agency, website = management.first(websiteKeys))
}

internal fun campflareContact(contact: JsonObject?): CampgroundContact? {
    val phone = contact?.first(phoneKeys)
    val email = contact?.first(emailKeys)
    if (phone == null && email == null) return null
    return CampgroundContact(phone = phone, email = email)
}

private fun JsonObject.first(keys: List<String>): String? = keys.firstNotNullOfOrNull { stringField(it) }
