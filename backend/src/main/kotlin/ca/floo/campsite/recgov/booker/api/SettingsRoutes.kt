package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.auth.TokenManager
import ca.floo.campsite.recgov.booker.db.SettingsRepo
import ca.floo.campsite.recgov.booker.notifier.SlackNotifier
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MASK = "••••••••"

fun Route.settingsRoutes(
    settings: SettingsRepo,
    slack: SlackNotifier,
    tokenManager: TokenManager? = null,
) {
    get("/api/campsite/settings") {
        val all = settings.all()
        val token = all["recgov_token"].orEmpty()
        val info = RecgovAuth.tokenInfo(token)
        val masked =
            buildJsonObject {
                for ((k, v) in all) {
                    when (k) {
                        "slack_token", "recgov_cookies", "recgov_token", "recgov_refresh_creds" ->
                            if (v.isNotEmpty()) put(k, MASK) else put(k, "")
                        else -> put(k, v)
                    }
                }
                if (token.isNotEmpty()) {
                    info.expires?.let { put("recgov_token_expires", it.toString()) }
                    put("recgov_token_expired", info.expired)
                }
            }
        call.respondText(masked.toString())
    }

    post("/api/campsite/settings") {
        val body = parseJson(call.receiveText())
        val allowed = setOf("poll_interval", "slack_token", "slack_channel", "slack_enabled", "ridb_api_key")
        val updates = mutableMapOf<String, String>()
        for (key in allowed) {
            val v = body.string(key) ?: continue
            // Skip masked sentinel — UI sends '••••••••' to mean "leave unchanged".
            if (v == MASK) continue
            updates[key] = v
        }
        // recgov_cookies is a write-only field: parse it server-side and store
        // the extracted token + refresh creds, never the raw paste.
        body.string("recgov_cookies")?.takeIf { it.isNotEmpty() && it != MASK }?.let { raw ->
            applyCookiePaste(settings, raw)
            tokenManager?.reloadFromSettings()
        }
        if (updates.isNotEmpty()) settings.setMany(updates)
        call.respondText("""{"ok":true}""")
    }

    post("/api/campsite/settings/test-slack") {
        // Accepts optional {slack_token, slack_channel} in the body so the
        // onboarding wizard can prove credentials before persisting them.
        // Empty body falls back to saved settings (existing Settings-modal flow).
        val body = parseJson(call.receiveText())
        val candidateToken = body.string("slack_token")?.takeIf { it.isNotEmpty() && it != MASK }
        val candidateChannel = body.string("slack_channel")?.takeIf { it.isNotEmpty() }
        try {
            slack.sendTest(candidateToken, candidateChannel)
            call.respondText("""{"ok":true}""")
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, """{"error":"${e.message}"}""")
        }
    }

    // Pure-parsing health check on a pasted cURL / recaccount JSON / cookie
    // string. Saves whatever it can extract (Bearer token + refresh creds);
    // does not hit recreation.gov. Returns counts the UI uses to render
    // ✓/✗ next to the textarea.
    post("/api/campsite/settings/test-cookies") {
        val body = parseJson(call.receiveText())
        val raw = body.string("raw").orEmpty()
        if (raw.isEmpty()) {
            call.respondText("""{"loggedIn":false,"count":0,"hasBearer":false,"error":"empty input"}""")
            return@post
        }

        applyCookiePaste(settings, raw)
        tokenManager?.reloadFromSettings()

        val token = settings.get("recgov_token").orEmpty()
        val info = RecgovAuth.tokenInfo(token)
        val cookieStr = RecgovAuth.extractCookies(raw)
        val count = RecgovAuth.countCookies(cookieStr)
        val hasBearer = token.isNotEmpty()
        val loggedIn = hasBearer && !info.expired
        val resp =
            buildJsonObject {
                put("loggedIn", loggedIn)
                put("count", count)
                put("hasBearer", hasBearer)
                info.expires?.let { put("tokenExpires", it.toString()) }
                put("tokenExpired", info.expired)
            }
        call.respondText(resp.toString())
    }

    // "Test browser session" — repurposed: validate the stored token, and if
    // it's expired but we have refresh creds, mint a fresh one. No browser.
    // Delegates to TokenManager so all refresh paths share one mutex.
    post("/api/campsite/settings/test-chrome") {
        val token = settings.get("recgov_token").orEmpty()
        if (token.isEmpty()) {
            call.respondText("""{"loggedIn":false,"error":"no token saved"}""")
            return@post
        }

        val info = RecgovAuth.tokenInfo(token)
        if (!info.expired) {
            val resp =
                buildJsonObject {
                    put("loggedIn", true)
                    info.expires?.let { put("tokenExpires", it.toString()) }
                    put("tokenExpired", false)
                }
            call.respondText(resp.toString())
            return@post
        }

        val mgr = tokenManager
        if (mgr == null) {
            call.respondText("""{"loggedIn":false,"tokenExpired":true,"error":"token manager not wired"}""")
            return@post
        }
        when (val r = mgr.refreshNow()) {
            is TokenManager.RefreshResult.Ok -> {
                val resp =
                    buildJsonObject {
                        put("loggedIn", true)
                        (r.recaccount["expiration"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let {
                            put("tokenExpires", it)
                        }
                        put("tokenExpired", false)
                        put("refreshed", true)
                    }
                call.respondText(resp.toString())
            }
            is TokenManager.RefreshResult.NoCreds ->
                call.respondText("""{"loggedIn":false,"tokenExpired":true,"error":"token expired and no refresh creds saved"}""")
            is TokenManager.RefreshResult.NoToken ->
                call.respondText("""{"loggedIn":false,"error":"no token saved"}""")
            is TokenManager.RefreshResult.Failed ->
                call.respondText("""{"loggedIn":false,"tokenExpired":true,"error":"${r.reason}"}""")
        }
    }

    post("/api/campsite/settings/refresh-token") {
        // Delegates to TokenManager so SettingsRoutes and the scheduler-fired
        // TokenRefreshDue handler share one mutex'd refresh path. Returns
        // synchronously — the UI button needs the answer.
        val mgr = tokenManager
        if (mgr == null) {
            call.respond(HttpStatusCode.InternalServerError, """{"error":"token manager not wired"}""")
            return@post
        }
        when (val r = mgr.refreshNow()) {
            is TokenManager.RefreshResult.Ok -> {
                val resp =
                    buildJsonObject {
                        put("ok", true)
                        (r.recaccount["expiration"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let {
                            put("expires", it)
                        }
                    }
                call.respondText(resp.toString())
            }
            is TokenManager.RefreshResult.NoToken,
            is TokenManager.RefreshResult.NoCreds,
            -> call.respond(HttpStatusCode.BadRequest, """{"error":"no token or refresh creds saved"}""")
            is TokenManager.RefreshResult.Failed ->
                call.respond(HttpStatusCode.BadGateway, """{"error":"${r.reason}"}""")
        }
    }

    post("/api/campsite/settings/clear-session") {
        settings.setMany(
            mapOf(
                "recgov_token" to "",
                "recgov_refresh_creds" to "",
                "recgov_cookies" to "",
            ),
        )
        tokenManager?.clearCache()
        call.respondText("""{"ok":true}""")
    }
}

/**
 * Parse a cURL trace, recaccount JSON, or raw cookie string and persist
 * whatever recreation.gov auth bits we can extract. Same logic both endpoints
 * use, so write-once paths agree.
 */
private fun applyCookiePaste(
    settings: SettingsRepo,
    raw: String,
) {
    val updates = mutableMapOf<String, String>()
    RecgovAuth.extractBearer(raw)?.takeIf { it.isNotEmpty() }?.let {
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
