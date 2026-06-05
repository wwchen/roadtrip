package ca.floo.campsite.recgov.booker.auth

import ca.floo.campsite.recgov.booker.api.RecgovAuth
import ca.floo.campsite.recgov.booker.db.SettingsRepo
import ca.floo.campsite.recgov.booker.events.CampsiteEvent
import ca.floo.campsite.recgov.booker.events.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Backend-owned recreation.gov token lifecycle. Single source of truth — every
 * caller (cart-extend, claim, status, companion) reads here instead of pulling
 * the raw `recgov_token` setting directly.
 *
 * Two paths to a token:
 *   - [peek]: non-blocking, returns the cached/persisted access_token without
 *     refreshing. Use for status reads, header dot, and any consumer that can
 *     tolerate a stale token (the path will 401 and surface the failure).
 *   - [getFreshToken]: suspending, mutex-guarded. If the cached token is
 *     within [refreshAheadOfExpiry] of expiring (or already expired), refresh
 *     in-place. Use when the next call genuinely needs a working bearer.
 *
 * Subscribes to:
 *   - [CampsiteEvent.TokenRefreshDue] (scheduled): proactive refresh ahead of
 *     expiry so consumer paths stay fast.
 *   - [CampsiteEvent.UserRefreshToken] (UI button): explicit user request.
 *
 * Publishes:
 *   - [CampsiteEvent.TokenRefreshed] on success
 *   - [CampsiteEvent.TokenRefreshFailed] on failure
 */
class TokenManager(
    private val getSetting: (String) -> String?,
    private val setSetting: (String, String) -> Unit,
    private val bus: EventBus,
    private val scope: CoroutineScope,
    private val refreshAheadOfExpiry: Duration = Duration.ofMinutes(5),
) {
    /** Convenience constructor that delegates to [SettingsRepo]. */
    constructor(
        settings: SettingsRepo,
        bus: EventBus,
        scope: CoroutineScope,
        refreshAheadOfExpiry: Duration = Duration.ofMinutes(5),
    ) : this(settings::get, settings::set, bus, scope, refreshAheadOfExpiry)

    private val log = LoggerFactory.getLogger(TokenManager::class.java)
    private val mutex = Mutex()

    /** Cached recaccount-shaped JSON (the rec.gov refresh endpoint's response shape). */
    @Volatile
    private var cachedRecaccount: JsonObject? = null

    fun start() {
        // Hydrate cache from persisted settings at startup so peek() works
        // before the first refresh. The token may already be valid — no need
        // to hit rec.gov.
        val token = getSetting("recgov_token").orEmpty()
        if (token.isNotEmpty()) {
            cachedRecaccount = RecgovAuth.buildRecaccountFromToken(token)
        }

        scope.launch {
            bus.typedEvents.collect { env ->
                when (env.event) {
                    is CampsiteEvent.TokenRefreshDue,
                    is CampsiteEvent.UserRefreshToken,
                    -> handleRefreshTrigger()
                    else -> Unit
                }
            }
        }
    }

    /** Cached access_token without I/O. Null/empty if nothing's been saved yet. */
    fun peek(): String? = cachedRecaccount?.get("access_token")?.stringContent()?.takeIf { it.isNotEmpty() }

    /** Cached recaccount-shaped JSON for the companion's Playwright injection. Null when no token exists. */
    fun peekRecaccount(): JsonObject? = cachedRecaccount

    /**
     * Returns a non-expired access_token, refreshing if the current one is
     * within [refreshAheadOfExpiry] of expiry (or already expired). Null if
     * we have no token at all or refresh fails.
     */
    suspend fun getFreshToken(): String? = withFresh()?.get("access_token")?.stringContent()?.takeIf { it.isNotEmpty() }

    /** Like [getFreshToken] but returns the recaccount-shaped JSON. */
    suspend fun getFreshRecaccount(): JsonObject? = withFresh()

    /** Drop the in-memory cache. Called by SettingsRoutes /clear-session after wiping the persisted token. */
    fun clearCache() {
        cachedRecaccount = null
    }

    /**
     * Re-read `recgov_token` from settings and rebuild the cache. Call this
     * after any path that writes the token directly to the settings repo
     * (e.g. cookie paste). Future paths that go through [refreshNow] update
     * the cache automatically.
     */
    fun reloadFromSettings() {
        val token = getSetting("recgov_token").orEmpty()
        cachedRecaccount = if (token.isNotEmpty()) RecgovAuth.buildRecaccountFromToken(token) else null
    }

    /** Explicit refresh, ignoring the freshness threshold. Used by SettingsRoutes /refresh-token. */
    suspend fun refreshNow(): RefreshResult =
        mutex.withLock {
            val token = currentToken() ?: return RefreshResult.NoToken
            val creds = currentCreds() ?: return RefreshResult.NoCreds
            doRefresh(token, creds)
        }

    private suspend fun withFresh(): JsonObject? =
        mutex.withLock {
            val current = cachedRecaccount
            val token = current?.get("access_token")?.stringContent()?.takeIf { it.isNotEmpty() }
            if (token != null) {
                val info = RecgovAuth.tokenInfo(token)
                val safeUntil = info.expires?.minus(refreshAheadOfExpiry)
                if (safeUntil != null && Instant.now().isBefore(safeUntil)) {
                    return@withLock current
                }
            }
            val priorToken = token ?: getSetting("recgov_token").orEmpty().takeIf { it.isNotEmpty() } ?: return@withLock null
            val creds = currentCreds() ?: return@withLock current // best-effort: keep stale token if no creds
            when (val r = doRefresh(priorToken, creds)) {
                is RefreshResult.Ok -> r.recaccount
                else -> current
            }
        }

    private suspend fun handleRefreshTrigger() {
        val token = currentToken()
        if (token == null) {
            log.debug("TokenManager: refresh trigger fired but no token saved — skipping")
            return
        }
        val info = RecgovAuth.tokenInfo(token)
        val safeUntil = info.expires?.minus(refreshAheadOfExpiry)
        if (safeUntil != null && Instant.now().isBefore(safeUntil)) {
            log.debug("TokenManager: token still fresh until {}, skipping refresh", info.expires)
            return
        }
        when (val r = refreshNow()) {
            is RefreshResult.Ok -> log.info("TokenManager: refreshed (expires {})", r.recaccount["expiration"]?.stringContent())
            is RefreshResult.Failed -> log.info("TokenManager: refresh failed — {}", r.reason)
            is RefreshResult.NoToken -> log.debug("TokenManager: no token to refresh")
            is RefreshResult.NoCreds -> log.info("TokenManager: token expired but no refresh creds saved")
        }
    }

    private suspend fun doRefresh(
        token: String,
        creds: ca.floo.campsite.recgov.booker.api.RefreshCreds,
    ): RefreshResult {
        val recaccount = RecgovAuth.refreshRecaccount(token, creds)
        if (recaccount == null) {
            bus.publish(CampsiteEvent.TokenRefreshFailed(reason = "rec.gov refresh endpoint failed"))
            return RefreshResult.Failed("rec.gov refresh endpoint failed")
        }
        val newToken = recaccount["access_token"]?.stringContent().orEmpty()
        if (newToken.isEmpty()) {
            bus.publish(CampsiteEvent.TokenRefreshFailed(reason = "refresh response missing access_token"))
            return RefreshResult.Failed("refresh response missing access_token")
        }
        cachedRecaccount = recaccount
        setSetting("recgov_token", newToken)
        val expires = recaccount["expiration"]?.stringContent().orEmpty()
        bus.publish(CampsiteEvent.TokenRefreshed(expires = expires))
        return RefreshResult.Ok(recaccount)
    }

    private fun currentToken(): String? =
        cachedRecaccount?.get("access_token")?.stringContent()?.takeIf { it.isNotEmpty() }
            ?: getSetting("recgov_token")?.takeIf { it.isNotEmpty() }

    private fun currentCreds(): ca.floo.campsite.recgov.booker.api.RefreshCreds? {
        val raw = getSetting("recgov_refresh_creds").orEmpty()
        if (raw.isEmpty()) return null
        return RecgovAuth.extractRefreshCreds(raw)
    }

    sealed interface RefreshResult {
        data class Ok(
            val recaccount: JsonObject,
        ) : RefreshResult

        data class Failed(
            val reason: String,
        ) : RefreshResult

        data object NoToken : RefreshResult

        data object NoCreds : RefreshResult
    }
}

private fun kotlinx.serialization.json.JsonElement.stringContent(): String? = (this as? JsonPrimitive)?.content
