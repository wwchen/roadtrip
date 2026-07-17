package ca.floo.roadtrip.route.common

import io.ktor.server.application.ApplicationCall
import java.time.LocalDate
import java.time.OffsetDateTime

internal sealed interface OptionalQuery<out T> {
    data object Missing : OptionalQuery<Nothing>

    data class Parsed<T>(
        val value: T,
    ) : OptionalQuery<T>

    data class Invalid(
        val rawValue: String,
    ) : OptionalQuery<Nothing>
}

internal fun ApplicationCall.pathParam(name: String): String? = parameters[name]

internal fun ApplicationCall.longPath(name: String): Long? = pathParam(name)?.toLongOrNull()

internal fun ApplicationCall.queryParam(name: String): String? = request.queryParameters[name]

internal fun ApplicationCall.trimmedQuery(name: String): String = queryParam(name)?.trim().orEmpty()

internal fun ApplicationCall.matchingQuery(
    name: String,
    regex: Regex,
): String? = queryParam(name)?.takeIf { regex.matches(it) }

internal fun ApplicationCall.optionalLongQuery(name: String): Long? = queryParam(name)?.toLongOrNull()

internal fun ApplicationCall.optionalBooleanQuery(name: String): OptionalQuery<Boolean> =
    queryParam(name)?.let { value ->
        value.toBooleanStrictOrNull()?.let { parsed -> OptionalQuery.Parsed(parsed) }
            ?: OptionalQuery.Invalid(value)
    } ?: OptionalQuery.Missing

internal fun ApplicationCall.optionalDoubleQuery(name: String): OptionalQuery<Double> =
    queryParam(name)?.let { value ->
        value.toDoubleOrNull()?.let { parsed -> OptionalQuery.Parsed(parsed) }
            ?: OptionalQuery.Invalid(value)
    } ?: OptionalQuery.Missing

internal fun ApplicationCall.boundedIntQuery(
    name: String,
    default: Int,
    range: IntRange,
): Int = (queryParam(name)?.toIntOrNull() ?: default).coerceIn(range)

internal fun ApplicationCall.intQueryAtLeast(
    name: String,
    default: Int,
    min: Int,
): Int = (queryParam(name)?.toIntOrNull() ?: default).coerceAtLeast(min)

internal fun ApplicationCall.optionalDateQuery(name: String): LocalDate? = queryParam(name)?.let(LocalDate::parse)

internal fun ApplicationCall.optionalOffsetDateTimeQuery(name: String): OffsetDateTime? =
    queryParam(name)?.let { value ->
        runCatching { OffsetDateTime.parse(value) }.getOrNull()
    }

internal fun ApplicationCall.splitQueryValues(vararg names: String): List<String> =
    names
        .flatMap { name -> request.queryParameters.getAll(name).orEmpty() }
        .flatMap { value -> value.split(",") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }

internal fun ApplicationCall.queryValues(vararg names: String): List<String> =
    splitQueryValues(*names)
        .distinct()

internal fun ApplicationCall.dateQueryValues(name: String): List<LocalDate> =
    queryValues(name).mapNotNull { value ->
        runCatching { LocalDate.parse(value) }.getOrNull()
    }
