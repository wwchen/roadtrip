package ca.floo.roadtrip.route.auth

import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.route.common.principalAttributeKey
import io.ktor.server.application.createApplicationPlugin

/**
 * Resolves the session cookie to a [Principal] once per request and makes it
 * ambient (see `ApplicationCall.principal()`). Installed once, application-wide.
 *
 * Runs for every request, including anonymous ones — [Principal.Anonymous] is a
 * value, not an absence — so a public page can render "you have 3 watches"
 * without the route becoming gated. Resolution and enforcement are separate:
 * this only resolves; `.access(...)` enforces.
 */
val roadtripAuthorization =
    createApplicationPlugin("roadtripAuthorization", ::RoadtripAuthorizationConfig) {
        val resolvePrincipal = pluginConfig.resolvePrincipal
        onCall { call ->
            call.attributes.put(principalAttributeKey, resolvePrincipal(call.request.sessionToken()))
        }
    }

class RoadtripAuthorizationConfig {
    /**
     * Maps a raw session token (or null) to a [Principal]. Defaults to always
     * anonymous, which is the correct behaviour when auth is not configured:
     * every request resolves to [Principal.Anonymous].
     */
    var resolvePrincipal: (String?) -> Principal = { Principal.Anonymous }
}
