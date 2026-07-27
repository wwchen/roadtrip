package ca.floo.roadtrip.route.api.docs

import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.access
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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

private const val API_DOCS_PATH = "/api/docs"
private const val API_PATH_PREFIX = "/api/"
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

@OptIn(ExperimentalSerializationApi::class)
private val openApiJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

internal fun Route.apiDocsRoutes() {
    swaggerUI(API_DOCS_PATH) {
        info = roadtripOpenApiInfo
        source = roadtripOpenApiSource()
    }

    route(API_DOCS_PATH) {
        get("/openapi.json") {
            val doc = OpenApiDoc(info = roadtripOpenApiInfo) + call.application.roadtripOpenApiRoutes()
            call.respondEncodedJson(openApiJson, doc)
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
    val isApiPath = path.startsWith(API_PATH_PREFIX) && path != API_DOCS_PATH && !path.startsWith("$API_DOCS_PATH/")
    return isApiPath || path.startsWith(TEST_PATH_PREFIX)
}
