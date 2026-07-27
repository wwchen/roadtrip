package ca.floo.roadtrip.route.common

import ca.floo.roadtrip.model.domain.auth.AccessCheck
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.model.domain.auth.check
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.util.AttributeKey

internal val routeAccessAttributeKey = AttributeKey<RouteAccess>("roadtrip.route.access")
internal val principalAttributeKey = AttributeKey<Principal>("roadtrip.principal")

private const val ERROR_UNAUTHENTICATED = "unauthenticated"
private const val ERROR_FORBIDDEN = "forbidden"

/**
 * The [Principal] resolved for this request by
 * [ca.floo.roadtrip.route.auth.roadtripAuthorization]. Falls back to
 * [Principal.Anonymous] if that plugin is not installed (e.g. a slim test app),
 * so a handler can always ask who the caller is without a null check.
 */
internal fun ApplicationCall.principal(): Principal = attributes.getOrNull(principalAttributeKey) ?: Principal.Anonymous

/**
 * Declares the access [level] of this route, alongside the existing
 * `describeApi(...)`. Two effects:
 *
 *  1. Records the level as a route attribute, which the coverage check reads
 *     (see [undeclaredAccessRoutes]) — a route with no `.access(...)` fails it.
 *  2. For a level that can refuse a caller ([RouteAccess.User] /
 *     [RouteAccess.HasRole]), installs an interceptor that enforces it against
 *     the ambient [principal] before the handler runs. [RouteAccess.Anonymous]
 *     and [RouteAccess.Signed] never refuse, so they install nothing and add no
 *     per-request cost.
 */
internal fun Route.access(level: RouteAccess): Route {
    attributes.put(routeAccessAttributeKey, level)
    if (level.canDeny()) installAccessGuard(level)
    return this
}

private fun RouteAccess.canDeny(): Boolean = this is RouteAccess.User || this is RouteAccess.HasRole

private fun Route.installAccessGuard(level: RouteAccess) {
    // A route node is an ApplicationCallPipeline; intercept the phase before the
    // handler (Call) so an unmet requirement short-circuits with the status and
    // the handler never runs.
    (this as RoutingNode).intercept(ApplicationCallPipeline.Plugins) {
        when (level.check(call.principal())) {
            AccessCheck.Allow -> {}
            AccessCheck.Unauthenticated -> {
                call.respondApiError(ERROR_UNAUTHENTICATED, HttpStatusCode.Unauthorized)
                finish()
            }
            AccessCheck.Forbidden -> {
                call.respondApiError(ERROR_FORBIDDEN, HttpStatusCode.Forbidden)
                finish()
            }
        }
    }
}
