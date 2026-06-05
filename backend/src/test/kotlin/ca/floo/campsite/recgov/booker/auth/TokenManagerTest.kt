package ca.floo.campsite.recgov.booker.auth

import ca.floo.campsite.recgov.booker.events.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for TokenManager. The actual rec.gov refresh path is covered by
 * [RecgovAuthTest]; these tests cover cache hydration from settings, peek vs.
 * getFreshToken semantics, and the no-token / no-creds branches.
 */
class TokenManagerTest {
    private fun fakeJwt(expSecondsFromNow: Long): String {
        val exp = Instant.now().epochSecond + expSecondsFromNow
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
        val body =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("""{"exp":$exp,"acct":{"account_id":"acct-1","email":"a@b.c"}}""".toByteArray())
        return "$header.$body.sig"
    }

    private fun fakeSettings(initial: Map<String, String> = emptyMap()): MutableMap<String, String> = ConcurrentHashMap(initial)

    private fun mgr(
        store: MutableMap<String, String>,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        bus: EventBus = EventBus(),
        refreshAhead: Duration = Duration.ofMinutes(5),
    ) = TokenManager(
        getSetting = { store[it] },
        setSetting = { k, v -> store[k] = v },
        bus = bus,
        scope = scope,
        refreshAheadOfExpiry = refreshAhead,
    )

    @Test
    fun `start hydrates cache from saved recgov_token`() {
        val token = fakeJwt(3600)
        val store = fakeSettings(mapOf("recgov_token" to token))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mgr = mgr(store, scope)
        assertNull(mgr.peek())
        mgr.start()
        assertEquals(token, mgr.peek())
        val rec: JsonObject = mgr.peekRecaccount()!!
        assertNotNull(rec["account"])
        scope.cancel()
    }

    @Test
    fun `peek returns null when no token saved`() {
        val mgr = mgr(fakeSettings())
        mgr.start()
        assertNull(mgr.peek())
    }

    @Test
    fun `getFreshToken returns cached when far from expiry`() =
        runBlocking {
            val token = fakeJwt(60 * 60)
            val mgr = mgr(fakeSettings(mapOf("recgov_token" to token)))
            mgr.start()
            assertEquals(token, mgr.getFreshToken())
        }

    @Test
    fun `getFreshToken returns null when nothing saved`() =
        runBlocking {
            val mgr = mgr(fakeSettings())
            mgr.start()
            assertNull(mgr.getFreshToken())
        }

    @Test
    fun `clearCache wipes the in-memory recaccount`() {
        val token = fakeJwt(3600)
        val mgr = mgr(fakeSettings(mapOf("recgov_token" to token)))
        mgr.start()
        assertNotNull(mgr.peek())
        mgr.clearCache()
        assertNull(mgr.peek())
    }

    @Test
    fun `reloadFromSettings re-hydrates after a cookie paste`() {
        val store = fakeSettings()
        val mgr = mgr(store)
        mgr.start()
        assertNull(mgr.peek())
        // Simulate the cookie-paste path: routes write to settings then call reload.
        val token = fakeJwt(3600)
        store["recgov_token"] = token
        assertNull(mgr.peek())
        mgr.reloadFromSettings()
        assertEquals(token, mgr.peek())
    }

    @Test
    fun `refreshNow returns NoToken when settings empty`() =
        runBlocking {
            val mgr = mgr(fakeSettings())
            mgr.start()
            assertTrue(mgr.refreshNow() is TokenManager.RefreshResult.NoToken)
        }

    @Test
    fun `refreshNow returns NoCreds when token saved but no refresh creds`() =
        runBlocking {
            val token = fakeJwt(60)
            val mgr = mgr(fakeSettings(mapOf("recgov_token" to token)))
            mgr.start()
            assertTrue(mgr.refreshNow() is TokenManager.RefreshResult.NoCreds)
        }
}
