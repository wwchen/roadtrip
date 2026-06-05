package ca.floo.campsite.recgov.booker.api

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
        val allowed = setOf("poll_interval", "slack_token", "slack_channel", "ridb_api_key")
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
        }
        if (updates.isNotEmpty()) settings.setMany(updates)
        call.respondText("""{"ok":true}""")
    }

    post("/api/campsite/settings/test-slack") {
        try {
            slack.sendTest()
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
    post("/api/campsite/settings/test-chrome") {
        val token = settings.get("recgov_token").orEmpty()
        val credsStr = settings.get("recgov_refresh_creds").orEmpty()
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

        // Token expired — try the refresh path
        val creds = credsStr.takeIf { it.isNotEmpty() }?.let { RecgovAuth.extractRefreshCreds(it) }
        if (creds == null) {
            call.respondText("""{"loggedIn":false,"tokenExpired":true,"error":"token expired and no refresh creds saved"}""")
            return@post
        }

        val refreshed = RecgovAuth.refreshAccessToken(token, creds)
        if (refreshed.isNullOrEmpty()) {
            call.respondText("""{"loggedIn":false,"tokenExpired":true,"error":"refresh failed"}""")
            return@post
        }
        settings.set("recgov_token", refreshed)
        val newInfo = RecgovAuth.tokenInfo(refreshed)
        val resp =
            buildJsonObject {
                put("loggedIn", true)
                newInfo.expires?.let { put("tokenExpires", it.toString()) }
                put("tokenExpired", false)
                put("refreshed", true)
            }
        call.respondText(resp.toString())
    }

    post("/api/campsite/settings/refresh-token") {
        val token = settings.get("recgov_token").orEmpty()
        val credsStr = settings.get("recgov_refresh_creds").orEmpty()
        val creds = credsStr.takeIf { it.isNotEmpty() }?.let { RecgovAuth.extractRefreshCreds(it) }
        if (token.isEmpty() || creds == null) {
            call.respond(HttpStatusCode.BadRequest, """{"error":"no token or refresh creds saved"}""")
            return@post
        }
        val refreshed = RecgovAuth.refreshAccessToken(token, creds)
        if (refreshed.isNullOrEmpty()) {
            call.respond(HttpStatusCode.BadGateway, """{"error":"refresh failed"}""")
            return@post
        }
        settings.set("recgov_token", refreshed)
        val info = RecgovAuth.tokenInfo(refreshed)
        val resp =
            buildJsonObject {
                put("ok", true)
                info.expires?.let { put("expires", it.toString()) }
            }
        call.respondText(resp.toString())
    }

    post("/api/campsite/settings/clear-session") {
        settings.setMany(
            mapOf(
                "recgov_token" to "",
                "recgov_refresh_creds" to "",
                "recgov_cookies" to "",
            ),
        )
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
