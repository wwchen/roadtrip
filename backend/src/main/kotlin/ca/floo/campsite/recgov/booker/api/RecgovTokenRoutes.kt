package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.auth.TokenManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Companion-facing token endpoint. The companion (Playwright auto-cart)
 * stops carrying its own refresh logic in PR 6 and reads from here every
 * time it needs to inject a recaccount into the browser session.
 *
 * Returns the recaccount-shaped JSON the companion's `injectRecaccount`
 * expects: `{access_token, expiration, account: {...}, is_guest, refresh_id}`.
 *
 * Trust boundary: shares localhost with the backend, same as
 * `/api/companion/heartbeat`. If the backend is ever exposed beyond the
 * cloudflared tunnel without ACLs, this needs auth.
 */
fun Route.recgovTokenRoutes(tokenManager: TokenManager) {
    get("/api/campsite/recgov/fresh-token") {
        val recaccount = tokenManager.getFreshRecaccount()
        if (recaccount == null) {
            call.respond(HttpStatusCode.NotFound, """{"error":"no recgov token saved"}""")
            return@get
        }
        call.respondText(recaccount.toString())
    }
}
