package ca.floo.roadtrip.route.api.health

import ca.floo.roadtrip.model.api.HealthResponseDto
import ca.floo.roadtrip.model.api.ReadinessResponseDto
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.service.health.ReadinessService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.time.Instant

/**
 * The two infra probes, deliberately answering different questions.
 *
 * `/api/health` is liveness and stays boring: it proves only that the Ktor app
 * booted and can answer. Making it depend on Postgres would have the
 * orchestrator kill and restart a perfectly good container during a database
 * outage.
 *
 * `/api/health/ready` is readiness — it probes dependencies and answers `503`
 * when they are down, which is what a deploy gate and a load balancer need. A
 * process that is alive but cannot reach its database must not be handed
 * traffic, and a deploy that produces one must not be declared successful.
 */
internal fun Route.healthRoutes(readiness: ReadinessService) {
    route("/api") {
        get("/health") {
            call.respondHealthJson(healthResponseDto(Instant.now().epochSecond))
        }.describeApi("health", "Application liveness probe")
            .access(RouteAccess.Anonymous)

        get("/health/ready") {
            val report = readiness.report()
            call.respondHealthJson(
                readinessResponseDto(report, Instant.now().epochSecond),
                if (report.isReady) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            )
        }.describeApi("health", "Application readiness probe (dependency-aware)")
            .access(RouteAccess.Anonymous)
    }
}

internal fun healthResponseDto(now: Long): HealthResponseDto = HealthResponseDto(now = now)

internal fun readinessResponseDto(
    report: ReadinessService.Report,
    now: Long,
): ReadinessResponseDto =
    ReadinessResponseDto(
        status = if (report.isReady) ReadinessResponseDto.State.READY else ReadinessResponseDto.State.NOT_READY,
        now = now,
        database =
            if (report.databaseReachable) {
                ReadinessResponseDto.Dependency.UP
            } else {
                ReadinessResponseDto.Dependency.DOWN
            },
    )

private suspend inline fun <reified T> ApplicationCall.respondHealthJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondEncodedJson(value, status)
}
