package ca.floo.roadtrip.model.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The one codec for the typed campground JSONB columns. Absent values are the
 * empty object / array on the wire and in the table; unknown stored keys are
 * ignored on read, so a column written before a field existed still decodes.
 */
object CampgroundColumnJson {
    const val EMPTY_OBJECT = "{}"

    @OptIn(ExperimentalSerializationApi::class)
    val json =
        Json {
            explicitNulls = false
            ignoreUnknownKeys = true
        }

    inline fun <reified T : Any> encodeObject(value: T?): String = value?.let { json.encodeToString(it) } ?: EMPTY_OBJECT

    inline fun <reified T : Any> encodeArray(values: List<T>): String = json.encodeToString(values)

    inline fun <reified T : Any> decodeObject(raw: String): T? {
        val element = json.parseToJsonElement(raw)
        if (element !is JsonObject || element.isEmpty()) return null
        return json.decodeFromJsonElement(element)
    }

    inline fun <reified T : Any> decodeArray(raw: String): List<T> {
        val element = json.parseToJsonElement(raw)
        if (element !is JsonArray) return emptyList()
        return json.decodeFromJsonElement(element)
    }

    inline fun <reified T : Any> element(value: T?): JsonElement = value?.let { json.encodeToJsonElement(it) } ?: JsonObject(emptyMap())

    inline fun <reified T : Any> elements(values: List<T>): JsonElement = json.encodeToJsonElement(values)
}
