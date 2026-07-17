package ca.floo.roadtrip.route.common

import io.ktor.server.application.ApplicationCall
import java.time.LocalDate
import java.time.OffsetDateTime

internal fun ApplicationCall.longPath(name: String): Long? = parameters[name]?.toLongOrNull()

internal fun ApplicationCall.optionalLongQuery(name: String): Long? = request.queryParameters[name]?.toLongOrNull()

internal fun ApplicationCall.boundedIntQuery(
    name: String,
    default: Int,
    range: IntRange,
): Int = (request.queryParameters[name]?.toIntOrNull() ?: default).coerceIn(range)

internal fun ApplicationCall.intQueryAtLeast(
    name: String,
    default: Int,
    min: Int,
): Int = (request.queryParameters[name]?.toIntOrNull() ?: default).coerceAtLeast(min)

internal fun ApplicationCall.optionalDateQuery(name: String): LocalDate? = request.queryParameters[name]?.let(LocalDate::parse)

internal fun ApplicationCall.optionalOffsetDateTimeQuery(name: String): OffsetDateTime? =
    request.queryParameters[name]?.let { value ->
        runCatching { OffsetDateTime.parse(value) }.getOrNull()
    }

internal fun ApplicationCall.queryValues(vararg names: String): List<String> =
    names
        .flatMap { name -> request.queryParameters.getAll(name).orEmpty() }
        .flatMap { value -> value.split(",") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

internal fun ApplicationCall.dateQueryValues(name: String): List<LocalDate> =
    queryValues(name).mapNotNull { value ->
        runCatching { LocalDate.parse(value) }.getOrNull()
    }
