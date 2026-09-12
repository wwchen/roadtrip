package ca.floo.roadtrip.route.common

import io.ktor.server.routing.OpenApiRoutePathFormat
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.path

// Swagger UI mounts framework-generated asset routes under SWAGGER_UI_PATH that
// cannot carry our attribute. openapi.json is our own route and is labelled, so
// it is deliberately not exempt — unlike the contract guard, which exempts the
// whole subtree because openapi.json answers with a Ktor type.
private const val OPENAPI_JSON_PATH = "/api/docs/openapi.json"

/**
 * Every method-leaf route under this node that reaches the handler without a
 * declared [ca.floo.roadtrip.model.domain.auth.RouteAccess], as readable paths.
 *
 * This is the build-time completeness check of RFC 0010: an empty list means the
 * whole tree is labelled. It runs both as a boot guard against the live routing
 * tree (see `registerKoinRoutes`) and from `RouteAccessCoverageTest` against the
 * fully mounted tree — the same job `JooqCodegenDriftTest` does for the schema.
 *
 * A leaf is covered if it, or any ancestor, declares access — so a group-level
 * `route("/x") { ... }.access(...)` covers its children.
 */
internal fun RoutingNode.undeclaredAccessRoutes(): List<String> {
    val missing = mutableListOf<String>()
    walkMethodLeaves { leaf ->
        if (!leaf.isAccessExempt() && !leaf.hasReachableAccess()) {
            missing += leaf.path(OpenApiRoutePathFormat)
        }
    }
    return missing.sorted()
}

private fun RoutingNode.hasReachableAccess(): Boolean {
    var node: RoutingNode? = this
    while (node != null) {
        if (node.attributes.contains(routeAccessAttributeKey)) return true
        node = node.parent
    }
    return false
}

private fun RoutingNode.isAccessExempt(): Boolean {
    val path = path(OpenApiRoutePathFormat)
    if (path == OPENAPI_JSON_PATH) return false
    return path == SWAGGER_UI_PATH || path.startsWith("$SWAGGER_UI_PATH/")
}
