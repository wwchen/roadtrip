package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.auth.TokenManager
import ca.floo.campsite.recgov.booker.db.MatchRepo
import ca.floo.campsite.recgov.booker.db.SettingsRepo
import ca.floo.campsite.recgov.booker.domain.Match
import ca.floo.campsite.recgov.booker.events.CampsiteEvent
import ca.floo.campsite.recgov.booker.events.EventBus
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

private const val MASK = "••••••••"

fun Route.bookingSessionRoutes(
    settings: SettingsRepo,
    tokenManager: TokenManager,
) {
    post("/api/campsite/booking/session/import", {
        tags = listOf("campsite-booking")
        summary = "Import and validate a pasted rec.gov recaccount or cURL token"
    }) {
        val body = call.receiveCampsiteJson<BookingSessionImportRequestDto>()
        val raw = body.raw.orEmpty()
        if (raw.isEmpty()) {
            call.respondJson(
                SessionProbeDto(
                    loggedIn = false,
                    count = 0,
                    hasBearer = false,
                    error = "empty input",
                ),
            )
            return@post
        }

        applyTokenPaste(settings, raw)
        tokenManager.reloadFromSettings()

        val token = settings.get("recgov_token").orEmpty()
        val info = RecgovAuth.tokenInfo(token)
        val cookieStr = RecgovAuth.extractCookies(raw)
        val count = RecgovAuth.countCookies(cookieStr)
        val hasBearer = token.isNotEmpty()
        val loggedIn = hasBearer && !info.expired
        call.respondJson(
            SessionProbeDto(
                loggedIn = loggedIn,
                count = count,
                hasBearer = hasBearer,
                tokenExpires = info.expires?.toString(),
                tokenExpired = info.expired,
            ),
        )
    }

    post("/api/campsite/booking/session/validate", {
        tags = listOf("campsite-booking")
        summary = "Validate the saved rec.gov token and refresh it when possible"
    }) {
        val token = settings.get("recgov_token").orEmpty()
        if (token.isEmpty()) {
            call.respondJson(SessionProbeDto(loggedIn = false, error = "no token saved"))
            return@post
        }

        val info = RecgovAuth.tokenInfo(token)
        if (!info.expired) {
            call.respondJson(
                SessionProbeDto(
                    loggedIn = true,
                    tokenExpires = info.expires?.toString(),
                    tokenExpired = false,
                ),
            )
            return@post
        }

        when (val r = tokenManager.refreshNow()) {
            is TokenManager.RefreshResult.Ok -> {
                call.respondJson(
                    SessionProbeDto(
                        loggedIn = true,
                        tokenExpires = (r.recaccount["expiration"] as? JsonPrimitive)?.content,
                        tokenExpired = false,
                        refreshed = true,
                    ),
                )
            }
            is TokenManager.RefreshResult.NoCreds ->
                call.respondJson(
                    SessionProbeDto(
                        loggedIn = false,
                        tokenExpired = true,
                        error = "token expired and no refresh creds saved",
                    ),
                )
            is TokenManager.RefreshResult.NoToken ->
                call.respondJson(SessionProbeDto(loggedIn = false, error = "no token saved"))
            is TokenManager.RefreshResult.Failed ->
                call.respondJson(
                    SessionProbeDto(
                        loggedIn = false,
                        tokenExpired = true,
                        error = r.reason,
                    ),
                )
        }
    }

    post("/api/campsite/booking/session/refresh", {
        tags = listOf("campsite-booking")
        summary = "Force a rec.gov JWT refresh now"
    }) {
        when (val r = tokenManager.refreshNow()) {
            is TokenManager.RefreshResult.Ok -> {
                call.respondJson(SessionRefreshDto(expires = (r.recaccount["expiration"] as? JsonPrimitive)?.content))
            }
            is TokenManager.RefreshResult.NoToken,
            is TokenManager.RefreshResult.NoCreds,
            -> call.respondJson(ErrorDto("no token or refresh creds saved"), status = HttpStatusCode.BadRequest)
            is TokenManager.RefreshResult.Failed ->
                call.respondJson(ErrorDto(r.reason), status = HttpStatusCode.BadGateway)
        }
    }

    post("/api/campsite/booking/session/clear", {
        tags = listOf("campsite-booking")
        summary = "Wipe saved rec.gov token and refresh credentials"
    }) {
        settings.setMany(
            mapOf(
                "recgov_token" to "",
                "recgov_refresh_creds" to "",
                "recgov_cookies" to "",
            ),
        )
        tokenManager.clearCache()
        call.respondJson(OkDto())
    }

    get("/api/campsite/booking/session/fresh-token", {
        tags = listOf("campsite-booking")
        summary = "Companion-facing rec.gov recaccount JSON; backend owns the refresh"
    }) {
        val recaccount = tokenManager.getFreshRecaccount()
        if (recaccount == null) {
            call.respondJson(ErrorDto("no recgov token saved"), status = HttpStatusCode.NotFound)
            return@get
        }
        call.respondJsonElement(recaccount)
    }
}

fun Route.bookingCartRoutes(
    matches: MatchRepo,
    bus: EventBus,
    settings: SettingsRepo,
    tokenManager: TokenManager,
) {
    post("/api/campsite/booking/matches/{id}/cart", {
        tags = listOf("campsite-booking")
        summary = "Issue an ATC (add-to-cart) request to rec.gov for this match"
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respondJson(ErrorDto("bad id"), status = HttpStatusCode.BadRequest)
        val m =
            matches.get(id)
                ?: return@post call.respondJson(ErrorDto("no such match"), status = HttpStatusCode.NotFound)
        val body = call.receiveCampsiteJson<BookingCartRequestDto>()
        val action = body.action

        if (action == "open") {
            val url = campsiteOpenUrl(m)
            call.respondJson(CartOpenDto(url = url))
            return@post
        }

        if (m.claimedBy != null || m.cartAdded != null) {
            matches.clearClaim(id)
        }
        val refreshed = matches.get(id) ?: m
        bus.publish(CampsiteEvent.MatchFound(matchJson = matchEnvelope(refreshed)))
        call.respondJson(CartQueuedDto())
    }

    post("/api/campsite/booking/cart/extend", {
        tags = listOf("campsite-booking")
        summary = "Extend the rec.gov cart-hold lease for an in-flight ATC"
    }) {
        val token =
            tokenManager.getFreshToken()
                ?: settings.get("recgov_token").orEmpty()
        if (token.isEmpty()) {
            call.respondJson(ErrorDto("no recgov token saved"), status = HttpStatusCode.BadRequest)
            return@post
        }
        val info = RecgovAuth.tokenInfo(token)
        if (info.expired) {
            call.respondJson(ErrorDto("recgov token expired"), status = HttpStatusCode.BadRequest)
            return@post
        }
        val ok = extendCartHold(token)
        if (ok) {
            call.respondJson(OkDto())
        } else {
            call.respondJson(ErrorDto("rec.gov refused cart extend"), status = HttpStatusCode.BadGateway)
        }
    }
}

private fun applyTokenPaste(
    settings: SettingsRepo,
    raw: String,
) {
    val updates = mutableMapOf<String, String>()
    RecgovAuth.extractBearer(raw)?.takeIf { it.isNotEmpty() && it != MASK }?.let {
        updates["recgov_token"] = it
    }
    RecgovAuth.extractAccessTokenFromRecaccount(raw)?.let {
        updates["recgov_token"] = it
    }
    RecgovAuth.extractRefreshCreds(raw)?.let {
        updates["recgov_refresh_creds"] = it.toJson()
    }
    if (updates.isNotEmpty()) settings.setMany(updates)
}

/** Computes the rec.gov campsite reservation URL for a match. Mirrors browser.js campsiteUrl. */
private fun campsiteOpenUrl(m: Match): String {
    val first = m.firstDate
    val lastNight = m.availableDates.lastOrNull() ?: first
    val checkout = LocalDate.parse(lastNight).plusDays(1).toString()
    return if (m.campsiteId.isNotBlank() && m.campsiteId != "0") {
        "https://www.recreation.gov/camping/campsites/${m.campsiteId}?startDate=$first&endDate=$checkout"
    } else {
        "https://www.recreation.gov/camping/campgrounds/${m.campgroundId}?startDate=$first&endDate=$checkout"
    }
}
