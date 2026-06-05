package ca.floo.campsite.recgov.booker.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val log = LoggerFactory.getLogger("CampgroundSearchRoutes")

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

fun Route.campgroundSearchRoutes() {
    get("/api/campsite/campgrounds/search") {
        val q = call.request.queryParameters["q"]
        if (q.isNullOrBlank()) {
            return@get call.respondText("""{"parks":[],"campgrounds":[]}""")
        }
        try {
            log.info("Search: rec.gov search \"{}\"", q)
            val parks = fetchSearch(q, "recarea", 5).mapPark()
            val campgrounds = fetchSearch(q, "campground", 15).mapCampground()
            call.respondText(
                buildJsonObject {
                    put("parks", JsonArray(parks))
                    put("campgrounds", JsonArray(campgrounds))
                }.toString(),
            )
        } catch (e: Exception) {
            // Fallback: if the input looks like an ID, return that as a single result.
            val idMatch = Regex("\\d{5,7}").find(q)
            if (idMatch != null) {
                call.respondText("""{"parks":[],"campgrounds":[{"id":"${idMatch.value}","name":"Campground ${idMatch.value}"}]}""")
            } else {
                call.respondText("""{"parks":[],"campgrounds":[]}""")
            }
        }
    }

    get("/api/campsite/campgrounds/in-park/{parkId}") {
        val parkName = call.request.queryParameters["name"].orEmpty()
        try {
            log.info("Search: rec.gov campgrounds in park \"{}\"", parkName)
            var campgrounds = fetchSearch(parkName, "campground", 50).mapCampground()
            if (parkName.isNotEmpty()) {
                val keywords =
                    parkName
                        .lowercase()
                        .split(Regex("\\s+"))
                        .filter { it.length > 3 && it !in NOISE }
                if (keywords.isNotEmpty()) {
                    val filtered =
                        campgrounds.filter { obj ->
                            val parent = ((obj as? JsonObject)?.get("parent_name") as? JsonPrimitive)?.content?.lowercase().orEmpty()
                            keywords.any { parent.contains(it) }
                        }
                    if (filtered.isNotEmpty()) campgrounds = filtered
                }
            }
            // sort by reviews desc
            val sorted =
                campgrounds.sortedByDescending { obj ->
                    ((obj as? JsonObject)?.get("reviews") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                }
            call.respondText(JsonArray(sorted).toString())
        } catch (e: Exception) {
            call.respondText("""[]""")
        }
    }
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

private fun List<JsonObject>.mapPark() =
    map { r ->
        val addr = (r["addresses"] as? JsonArray)?.firstOrNull() as? JsonObject
        buildJsonObject {
            put("id", (r["entity_id"] as? JsonPrimitive)?.content ?: "")
            put("name", (r["name"] as? JsonPrimitive)?.content ?: "")
            put("city", (addr?.get("city") as? JsonPrimitive)?.content ?: "")
            put("state", (addr?.get("state") as? JsonPrimitive)?.content ?: "")
        }
    }

private fun List<JsonObject>.mapCampground() =
    map { r ->
        val addr = (r["addresses"] as? JsonArray)?.firstOrNull() as? JsonObject
        buildJsonObject {
            put("id", (r["entity_id"] as? JsonPrimitive)?.content ?: "")
            put("name", (r["name"] as? JsonPrimitive)?.content ?: "")
            put("parent_name", (r["parent_name"] as? JsonPrimitive)?.content ?: "")
            put("parent_id", (r["parent_entity_id"] as? JsonPrimitive)?.content ?: "")
            put("city", (addr?.get("city") as? JsonPrimitive)?.content ?: "")
            put("state", (addr?.get("state") as? JsonPrimitive)?.content ?: "")
            // rec.gov returns average_rating as either a number or a numeric
            // string. Coerce to a JSON number (or null) so the frontend can
            // call .toFixed() on it.
            val rating = (r["average_rating"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            if (rating != null) put("rating", rating) else put("rating", JsonNull)
            put("reviews", (r["number_of_ratings"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0)
        }
    }
