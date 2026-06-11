package ca.floo.campsite.recgov.booker.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

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
    ): JsonObject =
        buildJsonObject {
            put("entity_id", id)
            put("name", name)
            put("parent_name", parentName)
            put("parent_entity_id", parentId)
            put(
                "addresses",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("city", city)
                            put("state", state)
                        },
                    ),
                ),
            )
            if (rating != null) put("average_rating", rating)
            put("number_of_ratings", reviews)
        }
}
