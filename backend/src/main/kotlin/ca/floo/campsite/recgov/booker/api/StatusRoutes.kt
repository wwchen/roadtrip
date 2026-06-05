package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.auth.TokenManager
import ca.floo.campsite.recgov.booker.db.SettingsRepo
import ca.floo.campsite.recgov.booker.monitoring.StatusMonitor
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Instant

fun Route.statusRoutes(
    settings: SettingsRepo,
    tokenManager: TokenManager? = null,
    monitor: StatusMonitor? = null,
) {
    get("/api/campsite/status") {
        val snapshot = monitor?.snapshot()
        val recgovReachable = snapshot?.recgovReachable ?: true // optimistic until bootstrap probe lands
        val checkedAt = (snapshot?.checkedAt ?: Instant.now()).toString()
        // Prefer TokenManager.peek so we get the cached recaccount when the
        // token has been refreshed since the persisted setting was last
        // touched. Falls back to settings for tests.
        val token = tokenManager?.peek() ?: settings.get("recgov_token").orEmpty()
        val loggedIn = token.isNotEmpty() && !RecgovAuth.tokenInfo(token).expired
        call.respondText("""{"recgovReachable":$recgovReachable,"loggedIn":$loggedIn,"checkedAt":"$checkedAt"}""")
    }
}
