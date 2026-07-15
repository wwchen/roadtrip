package ca.floo.roadtrip.service.booking.adapters.recgov

import ca.floo.roadtrip.clients.recgov.RecGovAuthClient
import ca.floo.roadtrip.clients.recgov.RecGovJwt
import ca.floo.roadtrip.clients.recgov.parseRecGovRecaccount
import ca.floo.roadtrip.clients.recgov.refreshCredentials
import ca.floo.roadtrip.models.api.RecGovRecaccountSchema
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

private const val RECGOV_REFRESH_AHEAD_MINUTES = 5L
private const val JWT_SEPARATOR_COUNT = 2

interface RecGovBookingSessionStore {
    suspend fun freshRecaccount(): RecGovRecaccountSchema?

    suspend fun importRecaccount(raw: String): RecGovRecaccountSchema?
}

class RecGovBookingSessionService(
    private val seedJson: String?,
    private val authClient: RecGovAuthClient,
    private val clock: Clock = Clock.systemUTC(),
    private val refreshAheadOfExpiry: Duration = Duration.ofMinutes(RECGOV_REFRESH_AHEAD_MINUTES),
) : RecGovBookingSessionStore,
    AutoCloseable {
    private val mutex = Mutex()

    @Volatile
    private var cachedRecaccount: RecGovRecaccountSchema? = parseSeed()

    override suspend fun freshRecaccount(): RecGovRecaccountSchema? =
        mutex.withLock {
            val current = cachedRecaccount ?: parseSeed()?.also { cachedRecaccount = it } ?: return@withLock null
            val token = current.accessToken.takeIf { it.isNotBlank() } ?: return@withLock null
            val expires = tokenExpiresAt(token, current) ?: return@withLock null
            val now = clock.instant()

            if (now.isBefore(expires.minus(refreshAheadOfExpiry))) return@withLock current

            val credentials = current.refreshCredentials()
            if (credentials == null) {
                return@withLock current.takeIf { now.isBefore(expires) }
            }

            val refreshed = authClient.refreshRecaccount(token, credentials)
            if (refreshed?.accessToken?.isNotBlank() == true) {
                cachedRecaccount = refreshed
                return@withLock refreshed
            }

            current.takeIf { now.isBefore(expires) }
        }

    override suspend fun importRecaccount(raw: String): RecGovRecaccountSchema? =
        mutex.withLock {
            val imported = parseRecaccount(raw)?.takeIf(::canCacheImportedRecaccount) ?: return@withLock null
            cachedRecaccount = imported
            imported
        }

    override fun close() {
        authClient.close()
    }

    private fun parseSeed(): RecGovRecaccountSchema? = parseRecaccount(seedJson)

    private fun parseRecaccount(rawJson: String?): RecGovRecaccountSchema? {
        val raw = rawJson?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return parseRecGovRecaccount(raw)
            ?.takeIf { it.accessToken.isNotBlank() }
            ?: raw
                .takeIf(::looksLikeJwt)
                ?.let(RecGovJwt::buildRecaccountFromToken)
                ?.takeIf { it.accessToken.isNotBlank() }
    }

    private fun canCacheImportedRecaccount(recaccount: RecGovRecaccountSchema): Boolean {
        val token = recaccount.accessToken.takeIf { it.isNotBlank() } ?: return false
        val expires = tokenExpiresAt(token, recaccount) ?: return false
        return clock.instant().isBefore(expires) || recaccount.refreshCredentials() != null
    }

    private fun tokenExpiresAt(
        token: String,
        recaccount: RecGovRecaccountSchema,
    ): Instant? =
        RecGovJwt.tokenInfo(token).expires
            ?: runCatching { Instant.parse(recaccount.expiration) }.getOrNull()

    private fun looksLikeJwt(raw: String): Boolean = raw.count { it == '.' } >= JWT_SEPARATOR_COUNT
}
