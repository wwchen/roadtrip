package ca.floo.roadtrip.service.etl.framework

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val PHOTO_URL_KEY = "url"

/** The campsite `photos` column: `[{ "url": ... }]` whatever the vendor's own shape, or null when it had none. */
fun photosPayload(urls: List<String>): JsonArray? =
    urls.takeIf { it.isNotEmpty() }?.let { found ->
        buildJsonArray {
            for (url in found) add(buildJsonObject { put(PHOTO_URL_KEY, url) })
        }
    }
