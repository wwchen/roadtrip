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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    ): List<JsonObject>
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
): List<JsonObject> {
    val url = "https://www.recreation.gov/api/search?q=${URLEncoder.encode(
        q,
        StandardCharsets.UTF_8,
    )}&entity_type=$entityType&exact=false&size=$size"
    val resp =
        httpClient.get(url) {
            header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
            header("Accept", "application/json")
        }
    val root = Json.parseToJsonElement(resp.bodyAsText()) as? JsonObject ?: return emptyList()
    val results = root["results"] as? JsonArray ?: return emptyList()
    return results.filterIsInstance<JsonObject>()
}

private fun List<JsonObject>.mapPark(): List<CampgroundSearchParkDto> =
    map { r ->
        val addr = (r["addresses"] as? JsonArray)?.firstOrNull() as? JsonObject
        CampgroundSearchParkDto(
            id = (r["entity_id"] as? JsonPrimitive)?.content ?: "",
            name = (r["name"] as? JsonPrimitive)?.content ?: "",
            city = (addr?.get("city") as? JsonPrimitive)?.content ?: "",
            state = (addr?.get("state") as? JsonPrimitive)?.content ?: "",
        )
    }

private fun List<JsonObject>.mapCampground(): List<CampgroundSearchCampgroundDto> =
    map { r ->
        val addr = (r["addresses"] as? JsonArray)?.firstOrNull() as? JsonObject
        val rating = (r["average_rating"] as? JsonPrimitive)?.content?.toDoubleOrNull()
        CampgroundSearchCampgroundDto(
            id = (r["entity_id"] as? JsonPrimitive)?.content ?: "",
            name = (r["name"] as? JsonPrimitive)?.content ?: "",
            parentName = (r["parent_name"] as? JsonPrimitive)?.content ?: "",
            parentId = (r["parent_entity_id"] as? JsonPrimitive)?.content ?: "",
            city = (addr?.get("city") as? JsonPrimitive)?.content ?: "",
            state = (addr?.get("state") as? JsonPrimitive)?.content ?: "",
            // rec.gov returns average_rating as either a number or a numeric
            // string. Coerce to a JSON number or explicit null.
            rating = rating,
            reviews = (r["number_of_ratings"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
        )
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
