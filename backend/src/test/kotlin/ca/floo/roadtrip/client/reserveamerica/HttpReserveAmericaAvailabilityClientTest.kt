package ca.floo.roadtrip.client.reserveamerica

import ca.floo.roadtrip.client.ScriptedHttpClient
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.util.Optional
import java.util.concurrent.CompletableFuture
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals

private const val HTTP_OK = 200

class HttpReserveAmericaAvailabilityClientTest {
    private fun page(code: String) =
        """
        <span id='resulttotal_dr_top'>1</span>
        <div class='siteListLabel'>
          <a href="/campsiteDetails.do?contractCode=NY&amp;siteId=253481&amp;parkId=489">042</a>
        </div>
        <div class='td status $code'>${code.uppercase()}</div>
        """.trimIndent()

    private fun ok(body: String): CompletableFuture<HttpResponse<String>> =
        CompletableFuture.completedFuture(
            object : HttpResponse<String> {
                override fun statusCode(): Int = HTTP_OK

                override fun request(): HttpRequest = HttpRequest.newBuilder(URI.create("https://example.test/")).build()

                override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()

                override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

                override fun body(): String = body

                override fun sslSession(): Optional<SSLSession> = Optional.empty()

                override fun uri(): URI = URI.create("https://example.test/")

                override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
            },
        )

    @Test
    fun `unrecognized cells from every matrix page are counted together`() =
        runBlocking {
            val bodies = ArrayDeque(listOf("<html></html>", page("q"), page("q")))
            val client = HttpReserveAmericaAvailabilityClient(ScriptedHttpClient { ok(bodies.removeFirst()) })

            val availability =
                client.fetch(
                    host = "newyorkstateparks.reserveamerica.com",
                    contractCode = "NY",
                    parkId = "489",
                    startDate = LocalDate.parse("2026-07-01"),
                    endDate = LocalDate.parse("2026-07-20"),
                )

            assertEquals(emptyList(), bodies.toList())
            assertEquals(mapOf("q/Q" to 2), availability.unrecognizedStatuses)
        }
}
