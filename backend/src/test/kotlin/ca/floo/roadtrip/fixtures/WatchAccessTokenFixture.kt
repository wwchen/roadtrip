package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.model.domain.auth.WatchCredential
import ca.floo.roadtrip.repo.WatchAccessTokenRepo
import ca.floo.roadtrip.service.auth.WatchAccessTokenService
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.time.Duration
import java.time.OffsetDateTime

/** The token a [fakeWatchAccessTokens] service mints, so assertions can name it. */
const val FAKE_WATCH_LINK_TOKEN = "fake-watch-link-token"

private val fakeTtl: Duration = Duration.ofDays(1)
private val fakeExpiry: OffsetDateTime = OffsetDateTime.parse("2099-01-01T00:00:00Z")

/**
 * A [WatchAccessTokenService] that mints one predictable token and resolves it,
 * with no database behind it.
 *
 * Every method a caller can reach is overridden, so the repo handed to `super`
 * is never touched — the same trick the fake repos in these tests already use to
 * stand in for a `DSLContext` that has no connection.
 *
 * @param mintedToken what [WatchAccessTokenService.issue] hands back, or null to
 *   simulate a mint that failed to produce a usable link.
 */
fun fakeWatchAccessTokens(
    mintedToken: String? = FAKE_WATCH_LINK_TOKEN,
    resolvesTo: Long? = null,
    ctx: DSLContext = DSL.using(SQLDialect.POSTGRES),
): WatchAccessTokenService =
    object : WatchAccessTokenService(WatchAccessTokenRepo(ctx), fakeTtl) {
        override fun issue(watchId: Long): IssuedWatchToken =
            IssuedWatchToken(
                token = mintedToken ?: error("no token"),
                expiresAt = fakeExpiry,
            )

        override fun resolve(token: String): WatchCredential.MagicLink? =
            (resolvesTo ?: return null).takeIf { token == mintedToken }?.let { WatchCredential.MagicLink(it) }

        override fun revokeAllForWatch(watchId: Long): Int = 0

        override fun deleteExpired(): Int = 0
    }
