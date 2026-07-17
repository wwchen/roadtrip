package ca.floo.roadtrip.route.api.slack

import ca.floo.roadtrip.client.slack.SlackSignatureVerifier
import ca.floo.roadtrip.service.notification.slack.SlackInteractivityHandler
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private object SlackInteractivityRoutes

private val log = LoggerFactory.getLogger(SlackInteractivityRoutes::class.java)

private const val SLACK_SIGNATURE_HEADER = "X-Slack-Signature"
private const val SLACK_TIMESTAMP_HEADER = "X-Slack-Request-Timestamp"

/**
 * Inbound Slack interactivity endpoint — the destination for `block_actions`
 * payloads fired when a user clicks a Pause / Resume / Delete button on a
 * watch card. The route:
 *
 *  1. Reads the raw form-encoded body (unmodified — the signature is over the
 *     bytes on the wire; any re-encoding would invalidate the check).
 *  2. Verifies the Slack signature via [SlackSignatureVerifier]. Anything
 *     unverifiable — no signing secret configured, missing / stale headers,
 *     tampered body — returns 401 with an empty body.
 *  3. Extracts the `payload` field, parses it as JSON.
 *  4. Ack's Slack with 200 immediately, then hands the payload to
 *     [SlackInteractivityHandler] on the background [scope]. Slack requires a
 *     response within 3s and doesn't wait for the mutation + response_url
 *     round-trip; the async handoff keeps us well inside that budget.
 *
 * Registered from [ca.floo.roadtrip.RoadtripRouting] only when a signing
 * secret is configured, so the endpoint is absent (404) on a Slack-disabled
 * install rather than answering 401 to every probe.
 *
 * Logs every request at INFO with enough detail to diagnose a mis-configured
 * app: whether Slack's headers arrived, whether the signature verified,
 * whether the payload parsed, and which `action_id` was routed. No secrets
 * or raw payload text hit the logs — only sizes, header presence, and the
 * public action id.
 */
internal fun Route.slackInteractivityRoute(
    verifier: SlackSignatureVerifier,
    handler: SlackInteractivityHandler,
    scope: CoroutineScope,
) {
    route("/api") {
        route("/slack") {
            post("/interactivity") {
                val body = call.receiveText()
                val timestamp = call.request.headers[SLACK_TIMESTAMP_HEADER]
                val signature = call.request.headers[SLACK_SIGNATURE_HEADER]
                val remoteHost = call.request.local.remoteHost
                val userAgent = call.request.headers["User-Agent"] ?: "-"

                log.info(
                    "Slack interactivity POST: remote={}, ua={}, body_bytes={}, X-Slack-Request-Timestamp={}, X-Slack-Signature={}",
                    remoteHost,
                    userAgent,
                    body.length,
                    timestamp ?: "<missing>",
                    signature?.let { "${it.take(11)}…" } ?: "<missing>",
                )

                val verified = verifier.verify(timestamp, signature, body.toByteArray(StandardCharsets.UTF_8))
                if (verified != SlackSignatureVerifier.Result.Verified) {
                    // Verifier already logged the specific reason at INFO — mirror the
                    // 401 so tail-following one log line is enough.
                    log.info("Slack interactivity → 401 (signature verify failed)")
                    call.respondText("", status = HttpStatusCode.Unauthorized)
                    return@post
                }

                val payloadJson = extractPayloadField(body)
                if (payloadJson == null) {
                    log.warn("Slack interactivity → 400: no 'payload' form field in body of {} bytes", body.length)
                    call.respondText("", status = HttpStatusCode.BadRequest)
                    return@post
                }

                val payload = SlackInteractivityHandler.parse(payloadJson)
                if (payload == null) {
                    log.warn(
                        "Slack interactivity → 400: 'payload' field is not a block_actions JSON (payload_bytes={})",
                        payloadJson.length,
                    )
                    call.respondText("", status = HttpStatusCode.BadRequest)
                    return@post
                }

                val actionIds = payload.actions.map { it.actionId }
                val values = payload.actions.map { it.value ?: "-" }
                log.info(
                    "Slack interactivity → 200, dispatching type={} actions={} values={} response_url_present={}",
                    payload.type,
                    actionIds,
                    values,
                    payload.responseUrl != null,
                )

                // Ack Slack first, mutate + update the card async — Slack's own guidance
                // is "reply within 3s"; a repo update + response_url roundtrip can eat
                // most of that budget on a bad network hop, so we don't block the ack.
                call.respondText("", status = HttpStatusCode.OK)
                scope.launch {
                    runCatching { handler.handle(payload) }
                        .onFailure {
                            log.warn(
                                "Slack interactivity handler failed for action(s) {}: {}",
                                actionIds,
                                it.message,
                            )
                        }
                }
            }
        }
    }
}

/** Pulls the URL-decoded `payload=...` value out of a form-encoded body. Slack
 *  sends a single key (`payload`), URL-encoded; we don't need a general form
 *  parser, and using one would consume the body Ktor already gave us. Returns
 *  null if the field is absent or empty. */
private fun extractPayloadField(body: String): String? {
    for (pair in body.split('&')) {
        val eq = pair.indexOf('=').takeIf { it >= 0 } ?: continue
        val key = pair.substring(0, eq)
        if (key != "payload") continue
        val raw = pair.substring(eq + 1)
        return runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
    return null
}
