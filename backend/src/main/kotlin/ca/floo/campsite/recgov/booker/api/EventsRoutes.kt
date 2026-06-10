package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.events.EventBus
import io.github.smiley4.ktorswaggerui.dsl.routing.documentation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.routing.Route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.collect

fun Route.eventsRoutes(bus: EventBus) {
    documentation({
        tags = listOf("campsite-events")
        summary = "Live campsite event stream (SSE)"
        description =
            "Server-Sent Events stream for campsite alert lifecycle updates: " +
            "poll start/done, matches, companion status, token refreshes, and rec.gov reachability transitions."
        request {
            queryParameter<Long>("lastEventId") {
                description = "Optional replay cursor for reconnects when Last-Event-ID cannot be sent."
                required = false
            }
        }
        response {
            code(HttpStatusCode.OK) {
                body<String> {
                    mediaTypes(ContentType("text", "event-stream"))
                    example("connected") {
                        value = "event: connected\ndata: {\"resumeFrom\":0}\n\n"
                    }
                    example("match") {
                        value = "id: 42\nevent: match\ndata: {\"site\":\"A12\"}\n\n"
                    }
                }
            }
        }
    }) {
        sse("/api/campsite/events") {
            // Header is set automatically by EventSource on auto-reconnect.
            // Query param is the manual fallback for explicit resume after a
            // process restart (browsers can't set initial Last-Event-ID).
            val headerId = call.request.header("Last-Event-ID")?.toLongOrNull()
            val queryId = call.request.queryParameters["lastEventId"]?.toLongOrNull()
            val lastEventId = headerId ?: queryId ?: 0L

            // Send a `connected` heartbeat first so the client knows the stream is up
            // even if there are no replay events. This event is NOT sequenced (id=null)
            // so it doesn't pollute the Last-Event-ID stream.
            send(ServerSentEvent(event = "connected", data = """{"resumeFrom":$lastEventId}"""))

            // Replay any missed events. SharedFlow's replay buffer holds the most
            // recent N envelopes; we filter by id > lastEventId.
            for (env in bus.replayBuffer().filter { it.id > lastEventId }) {
                sendEnvelope(env)
            }

            // Then live-subscribe. collect suspends; the SSE plugin closes the
            // session when the client disconnects, which cancels the coroutine.
            bus.events.collect { env ->
                if (env.id > lastEventId) sendEnvelope(env)
            }
        }
    }
}

private suspend fun ServerSSESession.sendEnvelope(env: ca.floo.campsite.recgov.booker.events.Envelope) {
    send(ServerSentEvent(id = env.id.toString(), event = env.type, data = env.data))
}
