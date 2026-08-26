package ca.floo.roadtrip.route.api

import ca.floo.roadtrip.config.CartoBasemapsConfig
import ca.floo.roadtrip.model.api.ClientConfigDto
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.roadtripApiJson
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

private const val API_PATH = "/api"
private const val CLIENT_CONFIG_PATH = "/client-config"
private const val CLIENT_CONFIG_TAG = "client-config"

internal fun Route.clientConfigRoutes(cartoBasemaps: CartoBasemapsConfig) {
    route(API_PATH) {
        get(CLIENT_CONFIG_PATH) {
            call.respondEncodedJson(
                roadtripApiJson,
                ClientConfigDto(cartoBasemapsApiKey = cartoBasemaps.apiKey),
            )
        }.describeApi(CLIENT_CONFIG_TAG, "Public runtime configuration for the browser")
            .access(RouteAccess.Anonymous)
    }
}
