package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.db.SettingsRepo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference

private val log = LoggerFactory.getLogger("StatusRoutes")

private val httpClient = HttpClient(CIO) { engine { requestTimeout = 6_000 } }

private data class CachedStatus(
    val recgovReachable: Boolean,
    val checkedAt: OffsetDateTime,
)

private val cache = AtomicReference<CachedStatus?>(null)
private const val CACHE_MS = 60_000L

fun Route.statusRoutes(settings: SettingsRepo) {
    get("/api/campsite/status") {
        val now = OffsetDateTime.now()
        val cur = cache.get()
        val fresh =
            cur != null &&
                java.time.Duration
                    .between(cur.checkedAt, now)
                    .toMillis() < CACHE_MS
        val recgovReachable =
            if (fresh) {
                cur!!.recgovReachable
            } else {
                val ok =
                    runCatching {
                        log.info("Status: checking rec.gov reachability")
                        httpClient
                            .get("https://www.recreation.gov/") {
                                header("User-Agent", "Mozilla/5.0")
                            }.status.value in 200..399
                    }.getOrDefault(false)
                cache.set(CachedStatus(ok, now))
                ok
            }
        val token = settings.get("recgov_token").orEmpty()
        val loggedIn = token.isNotEmpty() && !RecgovAuth.tokenInfo(token).expired
        call.respondText("""{"recgovReachable":$recgovReachable,"loggedIn":$loggedIn,"checkedAt":"$now"}""")
    }
}
