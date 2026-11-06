package ca.floo.roadtrip.route.internal

import ca.floo.roadtrip.model.api.AvailabilityEgressRequestDto
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.api.pois.mapProviderError
import ca.floo.roadtrip.route.common.BAD_REQUEST_ERROR
import ca.floo.roadtrip.route.common.RouteBodyResult
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.receiveJsonBody
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.service.api.availabilityErrorDto
import ca.floo.roadtrip.service.availability.AvailabilityEgressController
import ca.floo.roadtrip.service.availability.AvailabilityServiceError
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import ca.floo.roadtrip.route.api.pois.availabilityErrorDto as badWindowErrorDto

const val AVAILABILITY_EGRESS_PATH = "/api/internal/availability/fetch"

internal fun Route.availabilityEgressRoutes(
    controller: AvailabilityEgressController,
    enabled: Boolean,
) {
    if (!enabled) return
    post(AVAILABILITY_EGRESS_PATH) {
        val request =
            when (val body = call.receiveJsonBody<AvailabilityEgressRequestDto>()) {
                is RouteBodyResult.Invalid -> {
                    call.respondEncodedJson(availabilityErrorDto(BAD_REQUEST_ERROR), HttpStatusCode.BadRequest)
                    return@post
                }
                is RouteBodyResult.Valid -> body.value
            }
        try {
            call.respondEncodedJson(controller.fetch(request))
        } catch (e: AvailabilityServiceError) {
            val body = if (e is AvailabilityServiceError.BadDateWindow) badWindowErrorDto(e) else availabilityErrorDto(e.error)
            call.respondEncodedJson(body, e.egressStatus())
        } catch (e: AvailabilityProviderError) {
            val (status, error) = mapProviderError(e)
            call.respondEncodedJson(error, status)
        }
    }.describeApi(
        tag = "availability",
        summary = "Availability for one campground, fetched from mini-ca",
        description =
            "Internal, reached only through the egress host's access policy. Answers from mini-ca's stored snapshot " +
                "when it is fresh and from the vendor otherwise. Campgrounds and campsites are named " +
                "by `(data_provider, data_provider_ref)`; each cell carries the time it was observed.",
    ).access(RouteAccess.Anonymous)
}

private fun AvailabilityServiceError.egressStatus(): HttpStatusCode =
    when (this) {
        is AvailabilityServiceError.BadDateWindow -> HttpStatusCode.BadRequest
        AvailabilityServiceError.NotFound -> HttpStatusCode.NotFound
        AvailabilityServiceError.UnknownCampground -> HttpStatusCode.NotImplemented
    }
