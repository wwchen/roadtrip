package ca.floo.roadtrip.route.api.route

import ca.floo.roadtrip.support.RoutingFailure
import io.ktor.http.HttpStatusCode

internal data class RoutingFailureAnswer(
    val error: String,
    val status: HttpStatusCode,
)

internal fun RoutingFailure.answer(): RoutingFailureAnswer =
    when (this) {
        RoutingFailure.NOT_CONFIGURED -> RoutingFailureAnswer("routing_not_configured", HttpStatusCode.ServiceUnavailable)
        RoutingFailure.UNREACHABLE -> RoutingFailureAnswer("routing_unreachable", HttpStatusCode.BadGateway)
        RoutingFailure.UPSTREAM_AUTH -> RoutingFailureAnswer("routing_auth_failed", HttpStatusCode.BadGateway)
        RoutingFailure.UPSTREAM_RATE_LIMITED -> RoutingFailureAnswer("routing_rate_limited", HttpStatusCode.ServiceUnavailable)
        RoutingFailure.UPSTREAM_ERROR -> RoutingFailureAnswer("routing_unavailable", HttpStatusCode.BadGateway)
        RoutingFailure.NO_ROUTE -> RoutingFailureAnswer("route_not_found", HttpStatusCode.UnprocessableEntity)
        RoutingFailure.NO_SEGMENT -> RoutingFailureAnswer("route_no_segment", HttpStatusCode.UnprocessableEntity)
        RoutingFailure.INVALID_INPUT -> RoutingFailureAnswer("route_invalid_input", HttpStatusCode.UnprocessableEntity)
        RoutingFailure.CORRIDOR -> RoutingFailureAnswer("corridor_unavailable", HttpStatusCode.ServiceUnavailable)
    }
