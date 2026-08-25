package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.auth.WatchCredential
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.WatchAccessTokenRepo
import ca.floo.roadtrip.repo.seedCatalogPoi
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

private val ttl: Duration = Duration.ofDays(30)
private val t0: OffsetDateTime = OffsetDateTime.parse("2026-08-01T00:00:00Z")

/**
 * The magic-link token lifecycle, against a real database.
 *
 * These assertions are the security contract of the feature: what a token opens,
 * for how long, and the fact that the plaintext is not recoverable from storage.
 */
class WatchAccessTokenServiceTest : SharedDbTest() {
    private var userSeq = 0
    private var now: OffsetDateTime = t0

    // The database is shared across this class's methods, so every test starts
    // the clock in the same place — a leftover `now` from the previous test would
    // silently move the expiry of everything minted after it.
    @BeforeEach
    fun resetClock() {
        now = t0
    }

    private fun service(): WatchAccessTokenService = WatchAccessTokenService(WatchAccessTokenRepo(ctx), ttl) { now }

    private fun sha256Hex(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun seedWatch(): Long {
        val owner: UserId =
            UserRepo(ctx)
                .create(email = "watch-link-${userSeq++}@example.com", displayName = null, isEmailVerified = true)
                .id
        val poiId = ctx.seedCatalogPoi(sourceId = "watch-link-$userSeq", name = "Link POI", lon = -119.56, lat = 37.74).poiId
        return AvailabilityWatchRepo(ctx)
            .create(
                AvailabilityWatchRepo.CreateInput(
                    targets = listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiId, campsiteId = null)),
                    campsiteFilters = JsonObject(emptyMap()),
                    startDate = LocalDate.parse("2026-09-01"),
                    endDate = LocalDate.parse("2026-09-03"),
                    cadenceSec = null,
                    triggerKinds = listOf("email_notify"),
                    triggerConfig = JsonObject(emptyMap()),
                    stopWhenTriggered = false,
                    ownerUserId = owner.value,
                ),
            ).id
    }

    @Test
    fun `a minted token resolves to its own watch`() {
        val watchId = seedWatch()
        val issued = service().issue(watchId)

        assertEquals(WatchCredential.MagicLink(watchId), service().resolve(issued.token))
        assertEquals(now.plus(ttl), issued.expiresAt)
    }

    @Test
    fun `each mint is a distinct token`() {
        val watchId = seedWatch()
        val service = service()

        // Per-email tokens are what let us store only the hash: there is no
        // plaintext to re-read for a second send.
        assertNotEquals(service.issue(watchId).token, service.issue(watchId).token)
    }

    @Test
    fun `only the hash of a token is stored`() {
        val watchId = seedWatch()
        val issued = service().issue(watchId)

        val stored =
            ctx
                .fetch("SELECT encode(token_hash, 'hex') AS h FROM availability_watch_access_token WHERE watch_id = ?", watchId)
                .map { it.get("h", String::class.java) }

        // A database leak must not hand over working links, so what is on disk is
        // the digest and nothing else.
        assertEquals(listOf(sha256Hex(issued.token)), stored)
    }

    @Test
    fun `an unknown token resolves to nothing`() {
        seedWatch()
        assertNull(service().resolve("not-a-real-token"))
    }

    @Test
    fun `a revoked token stops resolving`() {
        val watchId = seedWatch()
        val issued = service().issue(watchId)

        assertEquals(1, service().revokeAllForWatch(watchId))
        assertNull(service().resolve(issued.token))
    }

    @Test
    fun `a token stops resolving once its ttl has passed`() {
        val watchId = seedWatch()
        val issued = service().issue(watchId)

        now = t0.plus(ttl).plusSeconds(1)
        assertNull(service().resolve(issued.token))
    }

    @Test
    fun `the sweep retires expired tokens and keeps live ones`() {
        val expiredWatch = seedWatch()
        service().issue(expiredWatch)
        now = t0.plus(ttl)
        val liveWatch = seedWatch()
        val live = service().issue(liveWatch)

        now = t0.plus(ttl).plusSeconds(1)
        service().deleteExpired()

        // Asserted per watch rather than as a count: this class's methods share
        // one database, so earlier tests' tokens are swept in the same call.
        assertEquals(0, tokenRows(expiredWatch))
        assertEquals(1, tokenRows(liveWatch))
        assertEquals(WatchCredential.MagicLink(liveWatch), service().resolve(live.token))
    }

    private fun tokenRows(watchId: Long): Int =
        ctx.fetchCount(
            ctx
                .selectFrom("availability_watch_access_token")
                .where("watch_id = ?", watchId),
        )

    @Test
    fun `deleting a watch takes its tokens with it`() {
        val watchId = seedWatch()
        val issued = service().issue(watchId)

        AvailabilityWatchRepo(ctx).delete(watchId)

        assertNull(service().resolve(issued.token))
    }
}
