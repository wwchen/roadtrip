package ca.floo.campsite.recgov.booker.notifier

import ca.floo.campsite.recgov.booker.domain.Alert
import ca.floo.campsite.recgov.booker.domain.Match
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SlackNotifier sends a chat.postMessage JSON payload. We intercept with
 * MockEngine, parse the captured body back to JSON, and assert on the
 * structure the user actually receives.
 */
class SlackNotifierTest {
    private val baseAlert =
        Alert(
            id = 1,
            campgroundId = "cg-1",
            campgroundName = "Tunnel Mountain",
            startDate = "2026-08-01",
            endDate = "2026-08-05",
            minNights = 2,
            createdAt = "2026-06-05T00:00:00Z",
        )

    private fun match(
        site: String,
        loop: String? = "Loop A",
        type: String? = "Standard",
        dates: List<String> = listOf("2026-08-01", "2026-08-02"),
    ) = Match(
        id = 1,
        alertId = 1,
        campgroundId = "cg-1",
        campsiteId = "site-$site",
        campsiteSite = site,
        campsiteLoop = loop,
        campsiteType = type,
        availableDates = dates,
        firstDate = dates.first(),
        nights = dates.size,
        foundAt = "2026-06-05T12:00:00Z",
    )

    private class MockSlack(
        respondOk: Boolean = true,
    ) {
        val captured = mutableListOf<String>()
        val client =
            HttpClient(
                MockEngine { request ->
                    captured += readBody(request.body)
                    respond(
                        content =
                            if (respondOk) {
                                """{"ok":"true"}"""
                            } else {
                                """{"ok":"false","error":"invalid_auth"}"""
                            },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            )

        // SlackNotifier sends JSON via setBody(String), which Ktor wraps as
        // TextContent. Anything else means the transport changed and the test
        // helper should follow it.
        private fun readBody(body: OutgoingContent): String =
            when (body) {
                is TextContent -> body.text
                is OutgoingContent.ByteArrayContent -> String(body.bytes())
                else -> error("unexpected body kind: ${body::class}")
            }
    }

    private fun settings(vararg pairs: Pair<String, String?>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    @Test
    fun `notifyBatch returns false when alert has notifySlack=false`() =
        runBlocking {
            val mock = MockSlack()
            val notifier = SlackNotifier(settings("slack_token" to "xoxb", "slack_channel" to "#c"), mock.client)
            val ok = notifier.notifyBatch(baseAlert.copy(notifySlack = false), listOf(match("A1")))
            assertFalse(ok)
            assertTrue(mock.captured.isEmpty(), "expected no Slack call")
        }

    @Test
    fun `notifyBatch returns false when slack_token is empty`() =
        runBlocking {
            val mock = MockSlack()
            val notifier = SlackNotifier(settings("slack_token" to "", "slack_channel" to "#c"), mock.client)
            val ok = notifier.notifyBatch(baseAlert, listOf(match("A1")))
            assertFalse(ok)
            assertTrue(mock.captured.isEmpty())
        }

    @Test
    fun `notifyBatch builds a header + section + button block payload`() =
        runBlocking {
            val mock = MockSlack()
            val notifier =
                SlackNotifier(settings("slack_token" to "xoxb-test", "slack_channel" to "#camp"), mock.client)
            val ok = notifier.notifyBatch(baseAlert, listOf(match("A1"), match("B7")))
            assertTrue(ok)
            assertEquals(1, mock.captured.size)

            val body = Json.parseToJsonElement(mock.captured[0]).jsonObject
            assertEquals("#camp", body["channel"]?.jsonPrimitive?.content)
            assertEquals(
                "⛺ 2 campsites available at Tunnel Mountain",
                body["text"]?.jsonPrimitive?.content,
            )
            val blocks = body["blocks"]?.jsonArray ?: error("no blocks")
            // header, section(fields), section(text=siteLines), actions
            assertEquals(4, blocks.size)
            assertEquals("header", blocks[0].jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals("actions", blocks[3].jsonObject["type"]?.jsonPrimitive?.content)

            val button = blocks[3].jsonObject["elements"]!!.jsonArray[0].jsonObject
            assertEquals(
                "Reserve Site A1 →",
                button["text"]!!.jsonObject["text"]?.jsonPrimitive?.content,
            )
            val url = button["url"]?.jsonPrimitive?.content ?: ""
            assertTrue(url.startsWith("https://www.recreation.gov/camping/campsites/site-A1?"), "got: $url")
            assertTrue(url.contains("startDate=2026-08-01"))
            assertTrue(url.contains("endDate=2026-08-03"), "checkout = last night + 1, got: $url")
        }

    @Test
    fun `notifyBatch truncates the site list at 10 entries`() =
        runBlocking {
            val mock = MockSlack()
            val notifier =
                SlackNotifier(settings("slack_token" to "xoxb", "slack_channel" to "#c"), mock.client)
            val matches = (1..15).map { match("S$it") }
            val ok = notifier.notifyBatch(baseAlert, matches)
            assertTrue(ok)

            val body = Json.parseToJsonElement(mock.captured[0]).jsonObject
            val fields = body["blocks"]!!.jsonArray[1].jsonObject["fields"]!!.jsonArray
            val sitesField = fields[1].jsonObject["text"]?.jsonPrimitive?.content ?: ""
            assertTrue(sitesField.contains("15 (showing 10)"), "got: $sitesField")
            val siteLines =
                body["blocks"]!!
                    .jsonArray[2]
                    .jsonObject["text"]!!
                    .jsonObject["text"]
                    ?.jsonPrimitive
                    ?.content ?: ""
            assertEquals(10, siteLines.count { it == '\n' } + 1)
        }

    @Test
    fun `notifyBatch escapes quotes in campground name`() =
        runBlocking {
            val mock = MockSlack()
            val notifier =
                SlackNotifier(settings("slack_token" to "xoxb", "slack_channel" to "#c"), mock.client)
            val ok =
                notifier.notifyBatch(
                    baseAlert.copy(campgroundName = "Big \"Quote\" Campground"),
                    listOf(match("A1")),
                )
            assertTrue(ok)
            // Body must still be valid JSON — Json.parseToJsonElement throws otherwise.
            val body = Json.parseToJsonElement(mock.captured[0]).jsonObject
            assertEquals(
                "⛺ 1 campsite available at Big \"Quote\" Campground",
                body["text"]?.jsonPrimitive?.content,
            )
        }

    @Test
    fun `notifyBatch returns false when Slack reports ok=false`() =
        runBlocking {
            val mock = MockSlack(respondOk = false)
            val notifier =
                SlackNotifier(settings("slack_token" to "xoxb", "slack_channel" to "#c"), mock.client)
            val ok = notifier.notifyBatch(baseAlert, listOf(match("A1")))
            assertFalse(ok)
        }

    @Test
    fun `match URL falls back to campground page when campsiteId is blank`() =
        runBlocking {
            val mock = MockSlack()
            val notifier =
                SlackNotifier(settings("slack_token" to "xoxb", "slack_channel" to "#c"), mock.client)
            val blank = match("A1").copy(campsiteId = "")
            val ok = notifier.notifyBatch(baseAlert, listOf(blank))
            assertTrue(ok)
            val body = Json.parseToJsonElement(mock.captured[0]).jsonObject
            val url =
                body["blocks"]!!
                    .jsonArray[3]
                    .jsonObject["elements"]!!
                    .jsonArray[0]
                    .jsonObject["url"]
                    ?.jsonPrimitive
                    ?.content ?: ""
            assertTrue(url.startsWith("https://www.recreation.gov/camping/campgrounds/cg-1?"), "got: $url")
        }

    @Test
    fun `toCheckoutDate adds one day to last night`() {
        assertEquals("2026-08-03", toCheckoutDate("2026-08-02"))
        assertEquals("2026-01-01", toCheckoutDate("2025-12-31"))
    }
}
