package ca.floo.roadtrip.routes

import io.ktor.server.application.ApplicationCall
import java.time.LocalDate

internal fun ApplicationCall.optionalDateQuery(name: String): LocalDate? = request.queryParameters[name]?.let(LocalDate::parse)

internal fun ApplicationCall.forceQuery(): Boolean = request.queryParameters["force"] == "1"

internal fun ApplicationCall.queryValues(vararg names: String): List<String> =
    names
        .flatMap { name -> request.queryParameters.getAll(name).orEmpty() }
        .flatMap { value -> value.split(",") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
