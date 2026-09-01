package ca.floo.roadtrip.client.companion

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Accessors for the companion's JSON shapes, shared by the two clients that
 * speak to it.
 *
 * Every field is optional *and* untyped by design: the companion answers with
 * whatever it knows, and a field that arrives as an object or an array is a
 * shape this layer simply does not have — not an exception. Hence `as?` rather
 * than `jsonPrimitive`, which throws on a mismatch; the clients promise never to
 * throw, and the settings status route (which catches only `SettingsError`)
 * would turn any escape into a 500 on the one read that must degrade instead.
 */
internal fun JsonObject.stringValue(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.booleanValue(name: String): Boolean? = (get(name) as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.objectValue(name: String): JsonObject? = get(name) as? JsonObject
