package ca.floo.roadtrip

import ca.floo.roadtrip.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

private const val MAIN_SOURCE_ROOT = "backend/src/main/kotlin"
private const val KOTLIN_EXTENSION = ".kt"

// Package prefixes, not import lines: a fully-qualified reference carries no
// import, and that spelling is in use here (RouteModule writes
// org.slf4j.LoggerFactory inline), so matching imports alone leaves the drift
// these rules exist to catch a supported way past them.
private const val JOOQ_PACKAGE = "org.jooq"
private const val KTOR_PACKAGE = "io.ktor"
private const val REPO_PACKAGE = "ca.floo.roadtrip.repo"

/** Persistence owns jOOQ; the infrastructure DI module is allowed to name it to wire it. */
private val jooqAllowed =
    listOf(
        "ca/floo/roadtrip/repo/",
        "ca/floo/roadtrip/db/",
        "ca/floo/roadtrip/di/InfraModule.kt",
    )

/** Builds an OIDC redirect URL with URLBuilder; it serves no HTTP. */
private const val KTOR_ALLOWED_UNDER_SERVICE = "ca/floo/roadtrip/service/auth/OidcIdentityProvider.kt"

/**
 * A `handle` route builder. Two alternatives, because the two spellings need
 * different exclusions: a trailing lambda is always the builder, receiver or not
 * (`someRoute.handle {`), while `handle(` has to spare both `handler.handle(payload)`
 * and a `fun handle(` declaration. Matched against code only: the prose that
 * explains this rule names the shape it forbids.
 */
@Suppress("TopLevelPropertyNaming")
private val BARE_HANDLE = Regex("handle\\s*\\{|(?<![.\\w])(?<!fun )handle\\s*\\(")

/**
 * The annotation the generator cannot see. The bare name, not `@EncodeDefault`:
 * a fully-qualified `@kotlinx.serialization.EncodeDefault` carries no `@` before
 * the name, and an aliased import still names it on the import line. Code only,
 * for the same reason as above.
 */
private const val ENCODE_DEFAULT = "EncodeDefault"

private const val ROUTING_DSL_PACKAGE = "io.ktor.server.routing"

@Suppress("TopLevelPropertyNaming")
private val COMMENT = Regex("/\\*.*?\\*/|//[^\n]*", RegexOption.DOT_MATCHES_ALL)

private const val SERVICE_PREFIX = "ca/floo/roadtrip/service/"
private const val ROUTE_PREFIX = "ca/floo/roadtrip/route/"
private const val MODEL_PREFIX = "ca/floo/roadtrip/model/"

/**
 * The files outside `route/` that reach for the routing DSL, and so could mount a
 * leaf. The bare-handle rule covers them; the same test asserts this list is
 * still the whole set, so the rule's scope cannot quietly fall behind the tree.
 */
private val routingDslOutsideRoute =
    listOf(
        "ca/floo/roadtrip/RoadtripRouting.kt",
        "ca/floo/roadtrip/di/RouteModule.kt",
    )

/**
 * Six seams that no compiler enforces. Each was a real drift: a service that
 * held a DSLContext, an adapter that imported HttpStatusCode to build a 503
 * nothing called, a route that reached past its controller into a repo, a
 * contract list that reached for Ktor's own `HttpMethod`. The last two are
 * shapes the boot guards and the type generator cannot see at all.
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

    /** The same tree with comments removed, for the rules whose own KDoc spells the forbidden shape. */
    private val code: List<Pair<String, String>> = sources.map { (path, text) -> path to COMMENT.replace(text, "") }

    @Test
    fun `the source tree is actually being read`() {
        check(sources.size > 100) { "expected the whole backend main tree under $MAIN_SOURCE_ROOT, found ${sources.size} files" }
    }

    @Test
    fun `only repo, db and the infrastructure DI module name jOOQ`() =
        assertEquals(
            emptyList(),
            sources
                .filter { (path, text) -> text.contains(JOOQ_PACKAGE) && jooqAllowed.none { path.startsWith(it) } }
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
                    path.startsWith(SERVICE_PREFIX) && text.contains(KTOR_PACKAGE) && path != KTOR_ALLOWED_UNDER_SERVICE
                }.map { it.first },
            "services do not construct HTTP responses. Return a typed outcome and let the route map it to a status.",
        )

    @Test
    fun `models never name Ktor`() =
        assertEquals(
            emptyList(),
            sources.filter { (path, text) -> path.startsWith(MODEL_PREFIX) && text.contains(KTOR_PACKAGE) }.map { it.first },
            "models are pure data shapes. ApiContract declares ApiMethod rather than importing io.ktor.http.HttpMethod.",
        )

    /**
     * `route("/x") { handle { … } }` answers every verb while installing no
     * `HttpMethodRouteSelector`, so `walkMethodLeaves` produces no leaf for it and
     * both boot guards — the RFC 0010 access one and the `ApiContract` one — pass
     * a route that declares neither. There are none today; this is what keeps it
     * that way, since the shape cannot be detected from the mounted tree.
     *
     * Scoped to everything that can mount one: `route/`, plus the two files that
     * reach for the routing DSL from outside it. The second assertion is what
     * keeps that list the whole set.
     */
    @Test
    fun `routes answer a named verb, never a bare handle`() {
        assertEquals(
            emptyList(),
            code.filter { (path, text) -> mountsRoutes(path) && BARE_HANDLE.containsMatchIn(text) }.map { it.first },
            "a bare handle{} leaf answers every verb and is invisible to both boot guards. " +
                "Mount it with get/post/put/patch/delete so it produces a method leaf.",
        )
        assertEquals(
            routingDslOutsideRoute,
            sources
                .filter { (path, text) -> !path.startsWith(ROUTE_PREFIX) && text.contains(ROUTING_DSL_PACKAGE) }
                .map { it.first },
            "a new file names the routing DSL from outside route/. Add it to routingDslOutsideRoute " +
                "so the rule above covers it.",
        )
    }

    private fun mountsRoutes(path: String): Boolean = path.startsWith(ROUTE_PREFIX) || path in routingDslOutsideRoute

    /**
     * The generated TypeScript's response rule is "optional iff nullable", and
     * one annotation breaks it: `EncodeDefault(NEVER)` makes the encoder omit a
     * key the descriptor still reports non-nullable, so the generated field would
     * be declared required and never arrive. It is not a `SerialInfo` annotation,
     * so it reaches neither `getElementAnnotations` nor `isElementOptional` and
     * the generator cannot refuse it by name. This is the layer that can.
     *
     * The ban is both modes, not just `NEVER`: `ALWAYS` is a no-op under
     * `encodeDefaults = true`, so a DTO naming the annotation at all is either
     * wrong or pointless, and one rule is cheaper to trust than two.
     */
    @Test
    fun `no DTO suppresses a default it still declares`() =
        assertEquals(
            emptyList(),
            code.filter { (path, text) -> path.startsWith(MODEL_PREFIX) && text.contains(ENCODE_DEFAULT) }.map { it.first },
            "EncodeDefault(NEVER) makes the encoder omit a field the generated TypeScript declares " +
                "required, and no gate can see it. Make the field nullable instead. EncodeDefault(ALWAYS) " +
                "is banned with it: encodeDefaults = true already does that, so it only misleads.",
        )

    @Test
    fun `routes never reach into a repo`() =
        assertEquals(
            emptyList(),
            sources.filter { (path, text) -> path.startsWith(ROUTE_PREFIX) && text.contains(REPO_PACKAGE) }.map { it.first },
            "routes are the HTTP shell. Put the read behind a service or controller.",
        )
}
