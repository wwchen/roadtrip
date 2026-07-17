package ca.floo.roadtrip

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide

internal fun Application.installRoadtripDependencies(boot: RoadtripBootContext) {
    dependencies {
        provide<RoadtripBootContext> { boot }
        provide(::startRoadtripRuntime)
    }
}

internal fun Application.installRoadtripAdminDependencies(boot: RoadtripBootContext) {
    dependencies {
        provide<RoadtripBootContext> { boot }
    }
}
