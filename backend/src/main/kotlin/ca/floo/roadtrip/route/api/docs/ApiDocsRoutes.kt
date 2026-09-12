package ca.floo.roadtrip.route.api.docs

import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.API_PREFIX
import ca.floo.roadtrip.route.common.SWAGGER_UI_PATH
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.underPrefix
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
            val doc = OpenApiDoc(info = roadtripOpenApiInfo) + call.application.roadtripOpenApiRoutes()
            call.respondEncodedJson(doc)
        }.hide().access(RouteAccess.Anonymous)
    }
}

private fun roadtripOpenApiSource(): OpenApiDocSource =
    OpenApiDocSource.Routing(
        contentType = ContentType.Application.Json,
        routes = { roadtripOpenApiRoutes() },
    )

private fun Application.roadtripOpenApiRoutes(): Sequence<Route> =
    routingRoot
        .descendants()
        .filter(::includeInRoadtripOpenApi)

private fun includeInRoadtripOpenApi(route: Route): Boolean {
    val path = route.path(OpenApiRoutePathFormat)
    val isApiPath = path.underPrefix(API_PREFIX) && path != SWAGGER_UI_PATH && !path.startsWith("$SWAGGER_UI_PATH/")
    return isApiPath || path.startsWith(TEST_PATH_PREFIX)
}
