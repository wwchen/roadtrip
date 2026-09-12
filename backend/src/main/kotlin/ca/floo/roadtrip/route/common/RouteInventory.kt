package ca.floo.roadtrip.route.common

import ca.floo.roadtrip.model.api.ApiContract
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.OpenApiRoutePathFormat
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.path

internal const val API_PREFIX = "/api/"
internal const val PASSWORD_AUTH_PREFIX = "/auth/password/"

/**
 * Root of the Swagger subtree. The one spelling: both guards in this package and
 * `apiDocsRoutes` measure against it. What each of them *does* with the subtree
 * differs — the access guard exempts the framework's assets but still requires
 * `openapi.json` to declare access, while [ApiContract] exempts the whole subtree.
 */
internal const val SWAGGER_UI_PATH = "/api/docs"

/** One mounted handler: the verb it answers and the path it answers on. */
internal data class RouteLeaf(
    val method: String,
    val path: String,
)

/** What the mounted tree and [ApiContract] disagree about, as readable lines. */
internal data class ContractDrift(
    val uncontractedRoutes: List<String>,
    val unmountedRows: List<String>,
)

/**
 * Visits every node that answers an HTTP method.
 *
 * The blind spot is a false *negative*, not a phantom leaf: a `route(...) { handle { } }`
 * node answers every verb while installing no `HttpMethodRouteSelector`, so it
 * produces no leaf and both guards that read this walk would let it serve
 * uncontracted and unlabelled. `RoutingNode.handlers` is internal to Ktor, so it
 * cannot be filtered on here; `LayeringGuardTest` keeps the whole `route` tree
 * free of a bare `handle` builder instead.
 */
internal fun RoutingNode.walkMethodLeaves(visit: (RoutingNode) -> Unit) {
    if (selector is HttpMethodRouteSelector) visit(this)
    children.forEach { it.walkMethodLeaves(visit) }
}

/** Every method leaf under this node, in tree order. */
internal fun RoutingNode.methodLeaves(): List<RouteLeaf> {
    val leaves = mutableListOf<RouteLeaf>()
    walkMethodLeaves { node ->
        leaves += RouteLeaf((node.selector as HttpMethodRouteSelector).method.value, node.path(OpenApiRoutePathFormat))
    }
    return leaves
}

/**
 * Whether [path] — spelled as `RoutingNode.path(OpenApiRoutePathFormat)` renders
 * it — is a path [ApiContract] is answerable for.
 *
 * The whole `/api/docs/` subtree is excluded: the Swagger assets are
 * framework-generated, and `openapi.json` answers with `io.ktor.openapi.OpenApiDoc`,
 * a Ktor type no DTO of ours describes.
 */
internal fun isContractedPath(path: String): Boolean {
    if (path == SWAGGER_UI_PATH || path.startsWith("$SWAGGER_UI_PATH/")) return false
    return path.underPrefix(API_PREFIX) || path.underPrefix(PASSWORD_AUTH_PREFIX)
}

/**
 * Whether [this] is the prefix root itself or something beneath it. A glob over
 * `/api` includes `/api` itself, and `get("/api") { … }` mounts a real,
 * reachable handler; a bare `startsWith("/api/")` would let it escape the
 * contract guard, the generator and the OpenAPI document at once. The one
 * spelling: [isContractedPath] and `apiDocsRoutes` both measure with it.
 */
internal fun String.underPrefix(prefix: String): Boolean = this == prefix.trimEnd('/') || startsWith(prefix)

/** The leaves [ApiContract] is answerable for. */
internal fun RoutingNode.contractedApiLeaves(): Set<RouteLeaf> = methodLeaves().filterTo(LinkedHashSet()) { isContractedPath(it.path) }

/**
 * Both directions at once: routes nobody declared, and declared rows nobody
 * mounted. A [ca.floo.roadtrip.model.api.ApiEndpoint.conditional] row is exempt
 * from the second half only — a mounted conditional route still needs its row,
 * which is why the first half compares against `keys()` rather than `requiredKeys()`.
 */
internal fun RoutingNode.apiContractDrift(): ContractDrift {
    val mounted = contractedApiLeaves().mapTo(LinkedHashSet()) { it.method to it.path }
    return ContractDrift(
        uncontractedRoutes = (mounted - ApiContract.keys()).map { "${it.first} ${it.second}" }.sorted(),
        unmountedRows = (ApiContract.requiredKeys() - mounted).map { "${it.first} ${it.second}" }.sorted(),
    )
}
