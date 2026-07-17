package ca.floo.roadtrip.route.common

import io.ktor.server.routing.Route
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi

@OptIn(ExperimentalKtorApi::class)
internal fun Route.describeApi(
    tag: String,
    summary: String,
    description: String? = null,
): Route =
    describe {
        tag(tag)
        this.summary = summary
        this.description = description
    }
