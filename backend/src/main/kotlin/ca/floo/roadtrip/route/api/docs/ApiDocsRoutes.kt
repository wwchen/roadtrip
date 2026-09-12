package ca.floo.roadtrip.route.api.docs

import ca.floo.roadtrip.apigen.OpenApiContractDocument
import ca.floo.roadtrip.model.api.ApiMethod
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.RouteLeaf
import ca.floo.roadtrip.route.common.SWAGGER_UI_PATH
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.declaredAccessByLeaf
import ca.floo.roadtrip.route.common.encodeApiJson
import ca.floo.roadtrip.route.common.isContractedPath
import ca.floo.roadtrip.route.common.respondEncodedJson
import io.ktor.http.ContentType
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.routing.OpenApiRoutePathFormat
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.openapi.plus
import io.ktor.server.routing.path
import io.ktor.server.routing.route
import io.ktor.server.routing.routingRoot
import java.util.concurrent.atomic.AtomicReference

private const val TEST_PATH_PREFIX = "/test/"
private const val OPENAPI_TITLE = "roadtrip API"
private const val OPENAPI_VERSION = "0.1.0"
private const val OPENAPI_DESCRIPTION = "Backend for roadtrip.floo.ca. Endpoints reflect the live routing tree."

private val roadtripOpenApiInfo =
    OpenApiInfo(
        title = OPENAPI_TITLE,
        version = OPENAPI_VERSION,
        description = OPENAPI_DESCRIPTION,
    )

internal fun Route.apiDocsRoutes() {
    swaggerUI(SWAGGER_UI_PATH) {
        info = roadtripOpenApiInfo
        source = roadtripOpenApiSource()
    }

    route(SWAGGER_UI_PATH) {
        get("/openapi.json") {
            call.respondEncodedJson(call.application.roadtripOpenApiDoc())
        }.hide().access(RouteAccess.Anonymous)
    }
}

/**
 * The document, both times it is served: the routing tree contributes the paths,
 * the tags and the summaries, and `ApiContract` contributes `components/schemas`,
 * each operation's request body and every status it can answer.
 */
private fun Application.roadtripOpenApiDoc(): OpenApiDoc {
    val fromTree = OpenApiDoc(info = roadtripOpenApiInfo) + roadtripOpenApiRoutes()
    val access = contractRouteAccess()
    return OpenApiContractDocument.apply(fromTree) { method, path -> access(method, path) }
}

/**
 * The Swagger UI's own copy of the spec, through the same contract pass.
 *
 * `OpenApiDocSource` is sealed, so the pass has to happen inside `Routing`'s two
 * hooks — and Ktor hands the application to `routes` and not to `serializeModel`,
 * which is why the access lookup is captured between them. `read` invokes the two
 * in that order, on the same call, so `serializeModel` always sees the lookup the
 * matching `routes` pass stored.
 */
private fun roadtripOpenApiSource(): OpenApiDocSource {
    val access = AtomicReference<(ApiMethod, String) -> RouteAccess?>(null)
    return OpenApiDocSource.Routing(
        contentType = ContentType.Application.Json,
        routes = {
            access.set(contractRouteAccess())
            roadtripOpenApiRoutes()
        },
        serializeModel = { doc ->
            encodeApiJson(
                OpenApiContractDocument.apply(doc) { method, path -> access.get()?.invoke(method, path) },
            )
        },
    )
}

private fun Application.contractRouteAccess(): (ApiMethod, String) -> RouteAccess? {
    val byLeaf = routingRoot.declaredAccessByLeaf()
    return { method, path -> byLeaf[RouteLeaf(method.wireValue, path)] }
}

private fun Application.roadtripOpenApiRoutes(): Sequence<Route> =
    routingRoot
        .descendants()
        .filter(::includeInRoadtripOpenApi)

/**
 * The contracted surface plus the `/test/` probes.
 *
 * `isContractedPath` is the one spelling `RouteInventory` already owns — under
 * `/api/` or `/auth/password/`, minus the whole Swagger subtree — so the two
 * `/auth/password/` rows reach the document and can carry their schemas.
 */
private fun includeInRoadtripOpenApi(route: Route): Boolean {
    val path = route.path(OpenApiRoutePathFormat)
    return isContractedPath(path) || path.startsWith(TEST_PATH_PREFIX)
}
