package ca.floo.roadtrip.route.common

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteQueryParamsTest {
    @Test
    fun `string helpers trim match split and dedupe query values`() =
        testApplication {
            application {
                routing {
                    get("/strings/{id}") {
                        val path = call.pathParam("id")
                        val q = call.trimmedQuery("q")
                        val match = call.matchingQuery("match", Regex("""\d+""")) ?: "none"
                        val values = call.splitQueryValues("categories").joinToString("|")
                        val dedupedValues = call.queryValues("categories").joinToString("|")

                        call.respondText("$path:$q:$match:$values:$dedupedValues")
                    }
                }
            }

            val response = client.get("/strings/abc?q=%20hello%20&match=123&categories=a,b&categories=b,c")

            assertEquals("abc:hello:123:a|b|b|c:a|b|c", response.bodyAsText())
        }

    @Test
    fun `optional typed helpers distinguish missing parsed and invalid values`() =
        testApplication {
            application {
                routing {
                    get("/parsed") {
                        call.respondText(
                            listOf(
                                call.optionalBooleanQuery("active").describe(),
                                call.optionalDoubleQuery("radius_miles").describe(),
                            ).joinToString("/"),
                        )
                    }
                }
            }

            assertEquals("missing/missing", client.get("/parsed").bodyAsText())
            assertEquals(
                "parsed:true/parsed:1.5",
                client.get("/parsed?active=true&radius_miles=1.5").bodyAsText(),
            )
            assertEquals(
                "invalid:yes/invalid:wide",
                client.get("/parsed?active=yes&radius_miles=wide").bodyAsText(),
            )
        }

    private fun OptionalQuery<*>.describe(): String =
        when (this) {
            OptionalQuery.Missing -> "missing"
            is OptionalQuery.Invalid -> "invalid:$rawValue"
            is OptionalQuery.Parsed -> "parsed:$value"
        }
}
