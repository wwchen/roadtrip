package ca.floo.roadtrip.route.common

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

internal const val INVALID_QUERY_ERROR = "invalid_query"

private const val INTEGER_EXPECTATION = "an integer"
private const val DATE_EXPECTATION = "a date (YYYY-MM-DD)"
private const val TIMESTAMP_EXPECTATION = "an ISO-8601 timestamp with offset"

internal fun ApplicationCall.pathParam(name: String): String? = parameters[name]

internal fun ApplicationCall.longPath(name: String): Long? = pathParam(name)?.toLongOrNull()

internal fun ApplicationCall.queryParam(name: String): String? = request.queryParameters[name]

internal fun ApplicationCall.trimmedQuery(name: String): String = queryParam(name)?.trim().orEmpty()

private fun invalid(
    name: String,
    rawValue: String,
    expectation: String,
): OptionalQuery.Invalid = OptionalQuery.Invalid(rawValue, "$name must be $expectation, got '$rawValue'")

private inline fun <T : Any> ApplicationCall.typedQuery(
    name: String,
    expectation: String,
    parse: (String) -> T?,
): OptionalQuery<T> =
    queryParam(name)?.let { value ->
        parse(value)?.let { parsed -> OptionalQuery.Parsed(parsed) } ?: invalid(name, value, expectation)
    } ?: OptionalQuery.Missing

internal fun ApplicationCall.optionalLongQuery(name: String): OptionalQuery<Long> =
    typedQuery(name, INTEGER_EXPECTATION, String::toLongOrNull)

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
): OptionalQuery<Int> = typedQuery(name, INTEGER_EXPECTATION, String::toIntOrNull).withDefault(default) { it.coerceIn(range) }

internal fun ApplicationCall.intQueryAtLeast(
    name: String,
    default: Int,
    min: Int,
): OptionalQuery<Int> = typedQuery(name, INTEGER_EXPECTATION, String::toIntOrNull).withDefault(default) { it.coerceAtLeast(min) }

internal fun ApplicationCall.optionalDateQuery(name: String): OptionalQuery<LocalDate> =
    typedQuery(name, DATE_EXPECTATION, ::parsedDateOrNull)

internal fun ApplicationCall.optionalOffsetDateTimeQuery(name: String): OptionalQuery<OffsetDateTime> =
    typedQuery(name, TIMESTAMP_EXPECTATION) { value ->
        try {
            OffsetDateTime.parse(value)
        } catch (e: DateTimeParseException) {
            null
        }
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

internal fun ApplicationCall.dateQueryValues(name: String): OptionalQuery<List<LocalDate>> {
    val values = queryValues(name)
    val unparseable = values.firstOrNull { parsedDateOrNull(it) == null }
    return when {
        unparseable != null -> invalid(name, unparseable, DATE_EXPECTATION)
        values.isEmpty() -> OptionalQuery.Missing
        else -> OptionalQuery.Parsed(values.map(LocalDate::parse))
    }
}

internal inline fun <T> OptionalQuery<T>.valueOrNull(onInvalid: (OptionalQuery.Invalid) -> Nothing): T? =
    when (this) {
        OptionalQuery.Missing -> null
        is OptionalQuery.Invalid -> onInvalid(this)
        is OptionalQuery.Parsed -> value
    }

internal inline fun <T : Any> OptionalQuery<T>.value(onInvalid: (OptionalQuery.Invalid) -> Nothing): T =
    checkNotNull(valueOrNull(onInvalid)) { "a query helper with a default answered Missing" }

internal suspend fun ApplicationCall.respondInvalidQuery(invalid: OptionalQuery.Invalid) {
    respondApiError(INVALID_QUERY_ERROR, HttpStatusCode.BadRequest, invalid.detail ?: invalid.rawValue)
}

private fun parsedDateOrNull(value: String): LocalDate? =
    try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        null
    }

private inline fun <T : Any> OptionalQuery<T>.withDefault(
    default: T,
    bound: (T) -> T,
): OptionalQuery<T> =
    when (this) {
        OptionalQuery.Missing -> OptionalQuery.Parsed(default)
        is OptionalQuery.Invalid -> this
        is OptionalQuery.Parsed -> OptionalQuery.Parsed(bound(value))
    }
