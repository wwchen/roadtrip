package ca.floo.roadtrip.route.common

import ca.floo.roadtrip.model.api.ApiContract
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.OpenApiRoutePathFormat
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.path

private const val API_PREFIX = "/api/"
private const val PASSWORD_AUTH_PREFIX = "/auth/password/"

// The Swagger UI subtree is framework-generated, and openapi.json answers with
// io.ktor.openapi.OpenApiDoc — a Ktor type no DTO of ours describes. Both are
// outside the contract, for the same reason the access guard exempts the subtree.
private const val API_DOCS_PATH = "/api/docs"

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

/** Every method leaf under this node, in tree order. */
internal fun RoutingNode.methodLeaves(): List<RouteLeaf> {
    val leaves = mutableListOf<RouteLeaf>()
    collectMethodLeaves(leaves)
    return leaves
}

/** The leaves [ApiContract] is answerable for. */
internal fun RoutingNode.contractedApiLeaves(): Set<RouteLeaf> =
    methodLeaves()
        .filter { it.path.startsWith(API_PREFIX) || it.path.startsWith(PASSWORD_AUTH_PREFIX) }
        .filterNot { it.path == API_DOCS_PATH || it.path.startsWith("$API_DOCS_PATH/") }
        .toSet()

/**
 * Both directions at once: routes nobody declared, and declared rows nobody
 * mounted. A [ca.floo.roadtrip.model.api.ApiEndpoint.conditional] row is exempt
 * from the second half only.
 */
internal fun RoutingNode.apiContractDrift(): ContractDrift {
    val mounted = contractedApiLeaves().mapTo(LinkedHashSet()) { it.method to it.path }
    return ContractDrift(
        uncontractedRoutes = (mounted - ApiContract.keys()).map { "${it.first} ${it.second}" }.sorted(),
        unmountedRows = (ApiContract.requiredKeys() - mounted).map { "${it.first} ${it.second}" }.sorted(),
    )
}

private fun RoutingNode.collectMethodLeaves(into: MutableList<RouteLeaf>) {
    (selector as? HttpMethodRouteSelector)?.let { into += RouteLeaf(it.method.value, path(OpenApiRoutePathFormat)) }
    children.forEach { it.collectMethodLeaves(into) }
}
