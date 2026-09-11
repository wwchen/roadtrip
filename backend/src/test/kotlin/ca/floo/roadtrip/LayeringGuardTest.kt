package ca.floo.roadtrip

import ca.floo.roadtrip.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

private const val MAIN_SOURCE_ROOT = "backend/src/main/kotlin"
private const val KOTLIN_EXTENSION = ".kt"

private const val JOOQ_IMPORT = "import org.jooq"
private const val KTOR_IMPORT = "import io.ktor"
private const val REPO_IMPORT = "import ca.floo.roadtrip.repo"

/** Persistence owns jOOQ; the two infrastructure DI modules are allowed to name it to wire it. */
private val jooqAllowed =
    listOf(
        "ca/floo/roadtrip/repo/",
        "ca/floo/roadtrip/db/",
        "ca/floo/roadtrip/di/InfraModule.kt",
        "ca/floo/roadtrip/di/RepoModule.kt",
    )

/** Builds an OIDC redirect URL with URLBuilder; it serves no HTTP. */
private const val KTOR_ALLOWED_UNDER_SERVICE = "ca/floo/roadtrip/service/auth/OidcIdentityProvider.kt"

private const val SERVICE_PREFIX = "ca/floo/roadtrip/service/"
private const val ROUTE_PREFIX = "ca/floo/roadtrip/route/"

/**
 * Three seams that no compiler enforces. Each was a real drift: a service that
 * held a DSLContext, an adapter that imported HttpStatusCode to build a 503
 * nothing called, a route that reached past its controller into a repo.
 */
class LayeringGuardTest {
    private val sourceRoot = File(repoRoot, MAIN_SOURCE_ROOT)

    private val sources: List<Pair<String, String>> =
        sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(KOTLIN_EXTENSION) }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath to it.readText() }
            .sortedBy { it.first }
            .toList()

    @Test
    fun `the source tree is actually being read`() {
        check(sources.size > 100) { "expected the whole backend main tree under $MAIN_SOURCE_ROOT, found ${sources.size} files" }
    }

    @Test
    fun `only repo, db and the infrastructure DI modules name jOOQ`() =
        assertEquals(
            emptyList(),
            sources
                .filter { (path, text) -> text.contains(JOOQ_IMPORT) && jooqAllowed.none { path.startsWith(it) } }
                .map { it.first },
            "jOOQ belongs to persistence. Take a repo or a UnitOfWork instead of a DSLContext, " +
                "and translate org.jooq.exception.DataAccessException inside the repo.",
        )

    @Test
    fun `only the OIDC redirect builder names Ktor under service`() =
        assertEquals(
            emptyList(),
            sources
                .filter { (path, text) ->
                    path.startsWith(SERVICE_PREFIX) && text.contains(KTOR_IMPORT) && path != KTOR_ALLOWED_UNDER_SERVICE
                }.map { it.first },
            "services do not construct HTTP responses. Return a typed outcome and let the route map it to a status.",
        )

    @Test
    fun `routes never reach into a repo`() =
        assertEquals(
            emptyList(),
            sources.filter { (path, text) -> path.startsWith(ROUTE_PREFIX) && text.contains(REPO_IMPORT) }.map { it.first },
            "routes are the HTTP shell. Put the read behind a service or controller.",
        )
}
