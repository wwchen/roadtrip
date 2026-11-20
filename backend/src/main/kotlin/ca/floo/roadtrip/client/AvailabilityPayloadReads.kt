package ca.floo.roadtrip.client

import ca.floo.roadtrip.model.availability.AvailabilityPayloadException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.format.DateTimeParseException

private const val PAYLOAD_EXCERPT_CHARS = 200
private const val ISO_DATE_CHARS = 10

internal class AvailabilityPayloadReads(
    private val provider: String,
) {
    fun unreadable(
        problem: String,
        cause: Throwable? = null,
    ): AvailabilityPayloadException = AvailabilityPayloadException(provider, problem, cause)

    fun excerpt(raw: String): String = raw.take(PAYLOAD_EXCERPT_CHARS)

    fun parseObject(body: String): JsonObject {
        val root =
            try {
                Json.parseToJsonElement(body)
            } catch (e: SerializationException) {
                throw unreadable("body is not JSON: ${excerpt(body)}", e)
            }
        return root as? JsonObject ?: throw unreadable("body is not a JSON object: ${excerpt(body)}")
    }

    fun obj(
        element: JsonElement?,
        where: String,
    ): JsonObject = element as? JsonObject ?: throw unreadable("$where is not an object: ${describe(element)}")

    fun array(
        element: JsonElement?,
        where: String,
    ): JsonArray = element as? JsonArray ?: throw unreadable("$where is not an array: ${describe(element)}")

    fun string(
        element: JsonElement?,
        where: String,
    ): String =
        literal(element, isString = true)?.content
            ?: throw unreadable("$where is not a string: ${describe(element)}")

    fun long(
        element: JsonElement?,
        where: String,
    ): Long =
        literal(element, isString = false)?.longOrNull
            ?: throw unreadable("$where is not an integer: ${describe(element)}")

    fun boolean(
        element: JsonElement?,
        where: String,
    ): Boolean =
        literal(element, isString = false)?.booleanOrNull
            ?: throw unreadable("$where is not a boolean: ${describe(element)}")

    private fun literal(
        element: JsonElement?,
        isString: Boolean,
    ): JsonPrimitive? = (element as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString == isString }

    fun isoDate(
        raw: String,
        where: String,
    ): LocalDate = parseDate(raw, raw, where)

    fun isoDateOfTimestamp(
        raw: String,
        where: String,
    ): LocalDate = parseDate(raw.take(ISO_DATE_CHARS), raw, where)

    private fun parseDate(
        date: String,
        raw: String,
        where: String,
    ): LocalDate =
        try {
            LocalDate.parse(date)
        } catch (e: DateTimeParseException) {
            throw unreadable("$where '$raw' is not an ISO date", e)
        }

    private fun describe(element: JsonElement?): String = element?.toString()?.let(::excerpt) ?: "missing"
}
