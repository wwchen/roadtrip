package ca.floo.roadtrip.service.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Encodes a value the mappers embed as a `JsonElement` inside a DTO; HTTP
 * serialization itself belongs to the route layer. The route's encoder adds
 * `ignoreUnknownKeys`, which only affects decoding, so an embedded value is
 * byte-identical to one the route encoded directly.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val embeddedApiJson: Json =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }
