package ca.floo.campsite.recgov.booker.api

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val log = LoggerFactory.getLogger("CampgroundSearchRoutes")

@OptIn(ExperimentalSerializationApi::class)
private val campgroundSearchJson =
    Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

private val httpClient =
    HttpClient(CIO) {
        engine { requestTimeout = 10_000 }
    }

private val NOISE =
    setOf(
        "national",
        "state",
        "park",
        "area",
        "forest",
        "recreation",
        "monument",
        "memorial",
        "wildlife",
        "refuge",
        "preserve",
        "seashore",
        "lakeshore",
    )

fun interface CampgroundSearchFetcher {
    suspend fun fetch(
        q: String,
        entityType: String,
        size: Int,
    ): List<CampgroundSearchUpstreamResultDto>
}

private val defaultCampgroundSearchFetcher =
    CampgroundSearchFetcher { q, entityType, size ->
        fetchSearch(q, entityType, size)
    }

fun Route.campgroundSearchRoutes(fetcher: CampgroundSearchFetcher = defaultCampgroundSearchFetcher) {
    get("/api/campsite/campgrounds/search", {
        tags = listOf("campsite-campgrounds")
        summary = "Search rec.gov for parks + campgrounds matching ?q="
    }) {
        val q = call.request.queryParameters["q"]
        if (q.isNullOrBlank()) {
            return@get call.respondCampgroundSearchJson(campgroundSearchResponse())
        }
        try {
            log.info("Search: rec.gov search \"{}\"", q)
            val parks = fetcher.fetch(q, "recarea", 5).mapPark()
            val campgrounds = fetcher.fetch(q, "campground", 15).mapCampground()
            call.respondCampgroundSearchJson(campgroundSearchResponse(parks, campgrounds))
        } catch (e: Exception) {
            // Fallback: if the input looks like an ID, return that as a single result.
            call.respondCampgroundSearchJson(fallbackCampgroundSearchResponse(q))
        }
    }

    get("/api/campsite/campgrounds/in-park/{parkId}", {
        tags = listOf("campsite-campgrounds")
        summary = "Campgrounds nested under a rec.gov park (filtered by park name)"
    }) {
        val parkName = call.request.queryParameters["name"].orEmpty()
        try {
            log.info("Search: rec.gov campgrounds in park \"{}\"", parkName)
            var campgrounds = fetcher.fetch(parkName, "campground", 50).mapCampground()
            if (parkName.isNotEmpty()) {
                val keywords =
                    parkName
                        .lowercase()
                        .split(Regex("\\s+"))
                        .filter { it.length > 3 && it !in NOISE }
                if (keywords.isNotEmpty()) {
                    val filtered =
                        campgrounds.filter { campground ->
                            val parent = campground.parentName.lowercase()
                            keywords.any { parent.contains(it) }
                        }
                    if (filtered.isNotEmpty()) campgrounds = filtered
                }
            }
            // sort by reviews desc
            call.respondCampgroundSearchJson(campgrounds.sortedByDescending { it.reviews })
        } catch (e: Exception) {
            call.respondCampgroundSearchJson(emptyList<CampgroundSearchCampgroundDto>())
        }
    }
}

private suspend inline fun <reified T> ApplicationCall.respondCampgroundSearchJson(value: T) {
    respondText(campgroundSearchJson.encodeToString(value), ContentType.Application.Json)
}

private fun campgroundSearchResponse(
    parks: List<CampgroundSearchParkDto> = emptyList(),
    campgrounds: List<CampgroundSearchCampgroundDto> = emptyList(),
): CampgroundSearchResponseDto = CampgroundSearchResponseDto(parks = parks, campgrounds = campgrounds)

private fun fallbackCampgroundSearchResponse(q: String): CampgroundSearchResponseDto {
    val idMatch = Regex("\\d{5,7}").find(q)
    if (idMatch == null) return campgroundSearchResponse()
    return campgroundSearchResponse(
        campgrounds =
            listOf(
                CampgroundSearchCampgroundDto(
                    id = idMatch.value,
                    name = "Campground ${idMatch.value}",
                ),
            ),
    )
}

private suspend fun fetchSearch(
    q: String,
    entityType: String,
    size: Int,
): List<CampgroundSearchUpstreamResultDto> {
    val url = "https://www.recreation.gov/api/search?q=${URLEncoder.encode(
        q,
        StandardCharsets.UTF_8,
    )}&entity_type=$entityType&exact=false&size=$size"
    val resp =
        httpClient.get(url) {
            header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
            header("Accept", "application/json")
        }
    return parseCampgroundSearchResults(resp.bodyAsText())
}

internal fun parseCampgroundSearchResults(body: String): List<CampgroundSearchUpstreamResultDto> =
    campgroundSearchJson
        .decodeFromString<CampgroundSearchUpstreamResponseDto>(body)
        .results

private fun List<CampgroundSearchUpstreamResultDto>.mapPark(): List<CampgroundSearchParkDto> =
    map { r ->
        val addr = r.addresses.firstOrNull()
        CampgroundSearchParkDto(
            id = r.entityId,
            name = r.name,
            city = addr?.city.orEmpty(),
            state = addr?.state.orEmpty(),
        )
    }

private fun List<CampgroundSearchUpstreamResultDto>.mapCampground(): List<CampgroundSearchCampgroundDto> =
    map { r ->
        val addr = r.addresses.firstOrNull()
        CampgroundSearchCampgroundDto(
            id = r.entityId,
            name = r.name,
            parentName = r.parentName,
            parentId = r.parentEntityId,
            city = addr?.city.orEmpty(),
            state = addr?.state.orEmpty(),
            rating = r.averageRating,
            reviews = r.numberOfRatings,
        )
    }

@Serializable
internal data class CampgroundSearchUpstreamResponseDto(
    val results: List<CampgroundSearchUpstreamResultDto> = emptyList(),
)

@Serializable
data class CampgroundSearchUpstreamResultDto(
    @SerialName("entity_id") val entityId: String = "",
    val name: String = "",
    val addresses: List<CampgroundSearchUpstreamAddressDto> = emptyList(),
    @SerialName("parent_name") val parentName: String = "",
    @SerialName("parent_entity_id") val parentEntityId: String = "",
    @Serializable(with = NullableFlexibleDoubleSerializer::class)
    @SerialName("average_rating")
    val averageRating: Double? = null,
    @Serializable(with = FlexibleIntSerializer::class)
    @SerialName("number_of_ratings")
    val numberOfRatings: Int = 0,
)

@Serializable
data class CampgroundSearchUpstreamAddressDto(
    val city: String = "",
    val state: String = "",
)

private object NullableFlexibleDoubleSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NullableFlexibleDouble", PrimitiveKind.DOUBLE)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(
        encoder: Encoder,
        value: Double?,
    ) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeDouble(value)
        }
    }

    override fun deserialize(decoder: Decoder): Double? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeDouble()
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
    }
}

private object FlexibleIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)

    override fun serialize(
        encoder: Encoder,
        value: Int,
    ) {
        encoder.encodeInt(value)
    }

    override fun deserialize(decoder: Decoder): Int {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeInt()
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return 0
        val primitive = element as? JsonPrimitive ?: return 0
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull() ?: 0
    }
}

@Serializable
private data class CampgroundSearchResponseDto(
    val parks: List<CampgroundSearchParkDto>,
    val campgrounds: List<CampgroundSearchCampgroundDto>,
)

@Serializable
private data class CampgroundSearchParkDto(
    val id: String,
    val name: String,
    val city: String,
    val state: String,
)

@Serializable
private data class CampgroundSearchCampgroundDto(
    val id: String,
    val name: String,
    @SerialName("parent_name") val parentName: String = "",
    @SerialName("parent_id") val parentId: String = "",
    val city: String = "",
    val state: String = "",
    val rating: Double? = null,
    val reviews: Int = 0,
)
