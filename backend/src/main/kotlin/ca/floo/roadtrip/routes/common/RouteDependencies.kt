package ca.floo.roadtrip.routes.common

import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.ktor.ext.getKoin

internal fun Route.routeKoin(): Koin = getKoin()
