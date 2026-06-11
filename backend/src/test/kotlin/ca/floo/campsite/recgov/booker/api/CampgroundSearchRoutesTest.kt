package ca.floo.campsite.recgov.booker.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
private val campgroundSearchEncodingTestJson =
    Json {
        encodeDefaults = true
        explicitNulls = true
    }

class CampgroundSearchRoutesTest {
    @Test
    fun `blank campground search returns empty response without fetching`() =
        testApplication {
            var calls = 0
            application {
                routing {
                    campgroundSearchRoutes { _, _, _ ->
                        calls += 1
                        emptyList()
                    }
                }
            }

            val response = client.get("/api/campsite/campgrounds/search")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json(response.bodyAsText())
            assertEquals(0, body["parks"]!!.jsonArray.size)
            assertEquals(0, body["campgrounds"]!!.jsonArray.size)
            assertEquals(0, calls)
        }

    @Test
    fun `campground search maps recgov park and campground results`() =
        testApplication {
            val calls = mutableListOf<String>()
            application {
                routing {
                    campgroundSearchRoutes { _, entityType, _ ->
                        calls += entityType
                        when (entityType) {
                            "recarea" ->
                                listOf(
                                    searchResult(
                                        id = "2991",
                                        name = "Yosemite National Park",
                                        city = "Yosemite",
                                        state = "CA",
                                    ),
                                )
                            "campground" ->
                                listOf(
                                    searchResult(
                                        id = "232447",
                                        name = "Upper Pines",
                                        parentName = "Yosemite National Park",
                                        parentId = "2991",
                                        rating = 4.6,
                                        reviews = 42,
                                    ),
                                )
                            else -> emptyList()
                        }
                    }
                }
            }

            val response = client.get("/api/campsite/campgrounds/search?q=yosemite")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json(response.bodyAsText())
            assertEquals(listOf("recarea", "campground"), calls)
            val park = body["parks"]!!.jsonArray.single().jsonObject
            assertEquals("2991", park["id"]!!.jsonPrimitive.content)
            val campground = body["campgrounds"]!!.jsonArray.single().jsonObject
            assertEquals("232447", campground["id"]!!.jsonPrimitive.content)
            assertEquals("Yosemite National Park", campground["parent_name"]!!.jsonPrimitive.content)
            assertEquals(4.6, campground["rating"]!!.jsonPrimitive.double)
            assertEquals("42", campground["reviews"]!!.jsonPrimitive.content)
        }

    @Test
    fun `campground search falls back to campground id response on fetch failure`() =
        testApplication {
            application {
                routing {
                    campgroundSearchRoutes { _, _, _ -> error("upstream down") }
                }
            }

            val response = client.get("/api/campsite/campgrounds/search?q=try%20232849")
            assertEquals(HttpStatusCode.OK, response.status)
            val campground =
                json(response.bodyAsText())["campgrounds"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("232849", campground["id"]!!.jsonPrimitive.content)
            assertEquals("Campground 232849", campground["name"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, campground["rating"])
        }

    @Test
    fun `in park route filters by parent name and sorts campgrounds by reviews`() =
        testApplication {
            application {
                routing {
                    campgroundSearchRoutes { _, entityType, _ ->
                        if (entityType == "campground") {
                            listOf(
                                searchResult(id = "1", name = "Lower", parentName = "Banff National Park", reviews = 5),
                                searchResult(id = "2", name = "Higher", parentName = "Banff National Park", reviews = 12),
                                searchResult(id = "3", name = "Other", parentName = "Jasper National Park", reviews = 99),
                            )
                        } else {
                            emptyList()
                        }
                    }
                }
            }

            val response = client.get("/api/campsite/campgrounds/in-park/2991?name=Banff%20National%20Park")
            assertEquals(HttpStatusCode.OK, response.status)
            val campgrounds = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(2, campgrounds.size)
            assertEquals("Higher", campgrounds[0].jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals("Lower", campgrounds[1].jsonObject["name"]!!.jsonPrimitive.content)
        }

    @Test
    fun `upstream dto decodes flexible rating and review primitives`() {
        val body =
            """
            {
              "results": [
                {
                  "entity_id": "232447",
                  "name": "Upper Pines",
                  "average_rating": "4.6",
                  "number_of_ratings": "42"
                },
                {
                  "entity_id": "232448",
                  "name": "Lower Pines",
                  "average_rating": null,
                  "number_of_ratings": 7
                }
              ]
            }
            """.trimIndent()

        val results =
            parseCampgroundSearchResults(body)

        assertEquals(4.6, results[0].averageRating)
        assertEquals(42, results[0].numberOfRatings)
        assertEquals(null, results[1].averageRating)
        assertEquals(7, results[1].numberOfRatings)
    }

    @Test
    fun `upstream dto encodes flexible rating and review primitives`() {
        val body =
            campgroundSearchEncodingTestJson.encodeToString(
                CampgroundSearchUpstreamResponseDto(
                    results =
                        listOf(
                            CampgroundSearchUpstreamResultDto(
                                entityId = "232447",
                                name = "Upper Pines",
                                addresses = listOf(CampgroundSearchUpstreamAddressDto(city = "Yosemite", state = "CA")),
                                averageRating = 4.6,
                                numberOfRatings = 42,
                            ),
                            CampgroundSearchUpstreamResultDto(
                                entityId = "232448",
                                name = "Lower Pines",
                                averageRating = null,
                                numberOfRatings = 0,
                            ),
                        ),
                ),
            )

        val root = Json.parseToJsonElement(body).jsonObject
        val results = root["results"]!!.jsonArray
        val first = results[0].jsonObject
        val second = results[1].jsonObject
        val address = first["addresses"]!!.jsonArray.single().jsonObject

        assertEquals(4.6, first["average_rating"]!!.jsonPrimitive.double)
        assertEquals("42", first["number_of_ratings"]!!.jsonPrimitive.content)
        assertEquals("Yosemite", address["city"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, second["average_rating"])
    }

    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun searchResult(
        id: String,
        name: String,
        parentName: String = "",
        parentId: String = "",
        city: String = "",
        state: String = "",
        rating: Double? = null,
        reviews: Int = 0,
    ): CampgroundSearchUpstreamResultDto =
        CampgroundSearchUpstreamResultDto(
            entityId = id,
            name = name,
            parentName = parentName,
            parentEntityId = parentId,
            addresses = listOf(CampgroundSearchUpstreamAddressDto(city = city, state = state)),
            averageRating = rating,
            numberOfRatings = reviews,
        )
}
