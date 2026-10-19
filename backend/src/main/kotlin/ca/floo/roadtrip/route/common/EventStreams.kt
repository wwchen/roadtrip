package ca.floo.roadtrip.route.common

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.sse.SSEServerContent
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.heartbeat
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.KSerializer
import java.time.Duration
import kotlin.time.toKotlinDuration

/** The comment a keep-alive carries. A comment, so a client never sees it as an event. */
private const val KEEP_ALIVE_COMMENT = "keep-alive"

/**
 * A `text/event-stream` response being written: each [send] is one named event
 * whose data is a DTO's JSON, encoded by the one encoder every JSON response uses.
 */
internal class EventStream(
    private val session: ServerSSESession,
) {
    suspend fun <T> send(
        event: String,
        serializer: KSerializer<T>,
        data: T,
    ) {
        session.send(ServerSentEvent(data = roadtripApiJson.encodeToString(serializer, data), event = event))
    }
}

/**
 * Answer as a `text/event-stream`, written by [block] and closed when it returns.
 *
 * Any method, not only the GET Ktor's `sse { }` routes take: a stream can be the
 * answer to a POST, which is how a request too big for a URL gets one. While
 * [block] has nothing to send, a keep-alive comment goes out every [heartbeat],
 * so a proxy does not take a quiet stream for a dead one. The client going away
 * cancels [block], and with it whatever it was waiting on.
 *
 * The wire format and the encoding are this function's; which events a route
 * sends, and what they mean, are the route's. The route's contract row names the
 * events (`ApiBody.events`), so the generated types and the body check know them.
 */
internal suspend fun ApplicationCall.respondEventStream(
    heartbeat: Duration,
    block: suspend EventStream.() -> Unit,
) {
    respond(
        SSEServerContent(this) {
            heartbeat {
                period = heartbeat.toKotlinDuration()
                event = ServerSentEvent(comments = KEEP_ALIVE_COMMENT)
            }
            EventStream(this).block()
        },
    )
}
