package ca.floo.campsite.recgov.booker.notifier

import ca.floo.campsite.recgov.booker.db.SettingsRepo
import ca.floo.campsite.recgov.booker.domain.Alert
import ca.floo.campsite.recgov.booker.domain.Match
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

class SlackNotifier(
    private val getSetting: (String) -> String?,
    private val client: HttpClient = HttpClient(CIO) { engine { requestTimeout = 8_000 } },
) {
    constructor(
        settings: SettingsRepo,
        client: HttpClient = HttpClient(CIO) { engine { requestTimeout = 8_000 } },
    ) : this(settings::get, client)

    private val log = LoggerFactory.getLogger(SlackNotifier::class.java)

    /** Sends one Slack message with all matches for an alert batch. Returns true on success. */
    suspend fun notifyBatch(
        alert: Alert,
        matches: List<Match>,
    ): Boolean {
        if (!alert.notifySlack) return false
        val token = getSetting("slack_token").orEmpty()
        val channel = getSetting("slack_channel").orEmpty()
        if (token.isEmpty() || channel.isEmpty()) {
            log.warn("Slack not configured — skipping notification")
            return false
        }
        val count = matches.size
        val first = matches.first()
        val firstUrl = matchUrl(first)
        val text = "⛺ $count campsite${if (count > 1) "s" else ""} available at ${alert.campgroundName}"

        val siteLines =
            matches.take(10).joinToString("\n") { m ->
                val loop = if (!m.campsiteLoop.isNullOrEmpty()) " (${m.campsiteLoop})" else ""
                val type = m.campsiteType ?: "N/A"
                "• Site *${m.campsiteSite}*$loop — ${m.availableDates.joinToString(", ")} _(${m.nights}n, $type)_"
            }

        val blocks = """[
            {"type":"header","text":{"type":"plain_text","text":"⛺ Campsites Available!","emoji":true}},
            {"type":"section","fields":[
                {"type":"mrkdwn","text":"*Campground*\n${esc(alert.campgroundName)}"},
                {"type":"mrkdwn","text":"*Sites found*\n$count${if (count > 10) " (showing 10)" else ""}"},
                {"type":"mrkdwn","text":"*Your window*\n${alert.startDate} → ${alert.endDate}"},
                {"type":"mrkdwn","text":"*Min nights*\n${alert.minNights}"}
            ]},
            {"type":"section","text":{"type":"mrkdwn","text":"${esc(siteLines)}"}},
            {"type":"actions","elements":[
                {"type":"button","text":{"type":"plain_text","text":"Reserve Site ${esc(first.campsiteSite ?: "")} →","emoji":true},
                 "url":"${esc(firstUrl)}","style":"primary"}
            ]}
        ]"""

        return runCatching { postSlack(token, channel, blocks, text) }
            .onSuccess { log.info("Slack notification sent to {}: {}", channel, text) }
            .onFailure { log.error("Slack notification failed: {}", it.message) }
            .isSuccess
    }

    suspend fun sendTest() {
        val token = getSetting("slack_token").orEmpty()
        val channel = getSetting("slack_channel").orEmpty()
        if (token.isEmpty()) throw IllegalStateException("slack_token not configured")
        if (channel.isEmpty()) throw IllegalStateException("slack_channel not configured")
        postSlack(token, channel, null, "✅ Campsite Alert test — Slack notifications are working!")
    }

    private suspend fun postSlack(
        token: String,
        channel: String,
        blocks: String?,
        text: String,
    ) {
        log.info("Slack: POST chat.postMessage to {}", channel)
        val body =
            if (blocks != null) {
                """{"channel":"${esc(channel)}","text":"${esc(text)}","blocks":$blocks}"""
            } else {
                """{"channel":"${esc(channel)}","text":"${esc(text)}"}"""
            }
        val resp =
            client.post("https://slack.com/api/chat.postMessage") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        val raw = resp.bodyAsText()
        val parsed = Json.parseToJsonElement(raw) as? JsonObject
        val ok = (parsed?.get("ok") as? JsonPrimitive)?.content == "true"
        if (!ok) {
            val err = (parsed?.get("error") as? JsonPrimitive)?.content ?: raw
            throw RuntimeException("Slack error: $err")
        }
    }

    private fun matchUrl(m: Match): String {
        val checkout = toCheckoutDate(m.availableDates.last())
        return if (m.campsiteId.isNotBlank()) {
            "https://www.recreation.gov/camping/campsites/${m.campsiteId}?startDate=${m.firstDate}&endDate=$checkout"
        } else {
            "https://www.recreation.gov/camping/campgrounds/${m.campgroundId}?startDate=${m.firstDate}&endDate=$checkout"
        }
    }
}

internal fun toCheckoutDate(lastNight: String): String =
    java.time.LocalDate
        .parse(lastNight)
        .plusDays(1)
        .toString()

private fun esc(s: String): String =
    buildString(s.length) {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
