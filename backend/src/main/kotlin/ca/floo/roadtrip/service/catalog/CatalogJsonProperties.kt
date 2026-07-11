package ca.floo.roadtrip.service.catalog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun JsonElement.stringProperty(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

internal fun JsonElement.firstObjectStringProperty(key: String): String? =
    (this as? JsonArray)
        ?.firstNotNullOfOrNull { element ->
            (element as? JsonObject)
                ?.let { (it[key] as? JsonPrimitive)?.contentOrNull }
                ?.trim()
                ?.takeIf { value -> value.isNotEmpty() }
        }
