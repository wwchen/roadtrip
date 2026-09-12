package ca.floo.roadtrip.fixtures

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Reads into an encoded OpenAPI document, shared by every test that asserts
// against one — the served spec and the hand-built documents alike.

private const val REF_KEY = "\$ref"

/** The `responses` map of one operation, by path and lowercase verb. */
internal fun responsesOf(
    paths: JsonObject,
    path: String,
    verb: String,
): JsonObject =
    paths
        .getValue(path)
        .jsonObject
        .getValue(verb)
        .jsonObject
        .getValue("responses")
        .jsonObject

/** The one media type's schema, from a `requestBody` or a `response`. */
internal fun schemaOf(holder: JsonElement): JsonObject =
    holder.jsonObject["content"]!!
        .jsonObject
        .values
        .single()
        .jsonObject["schema"]!!
        .jsonObject

/** That schema's component reference; fails when the schema is not a bare reference. */
internal fun schemaRef(holder: JsonElement): String = schemaOf(holder).getValue(REF_KEY).jsonPrimitive.content

/** Every component reference anywhere in the document, however deeply nested. */
internal fun refsIn(element: JsonElement): List<String> =
    when (element) {
        is JsonObject ->
            element.entries.flatMap { (key, value) ->
                if (key == REF_KEY) listOf(value.jsonPrimitive.content) else refsIn(value)
            }
        is JsonArray -> element.flatMap(::refsIn)
        else -> emptyList()
    }
