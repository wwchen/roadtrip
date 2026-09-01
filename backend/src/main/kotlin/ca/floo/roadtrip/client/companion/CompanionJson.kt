package ca.floo.roadtrip.client.companion

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Accessors for the companion's JSON shapes, shared by the two clients that
 * speak to it. Every field is optional by design: the companion answers with
 * whatever it knows, and a missing key is never an exception here.
 */
internal fun JsonObject.stringValue(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull

internal fun JsonObject.booleanValue(name: String): Boolean? = get(name)?.jsonPrimitive?.booleanOrNull

internal fun JsonObject.objectValue(name: String): JsonObject? = get(name) as? JsonObject
