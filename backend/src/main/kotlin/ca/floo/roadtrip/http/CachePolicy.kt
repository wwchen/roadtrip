package ca.floo.roadtrip.http

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.content.CachingOptions

fun cacheOptionsFor(
    path: String,
    contentType: ContentType?,
): CachingOptions? {
    val ct = contentType?.withoutParameters() ?: return null
    val type = ct.contentType
    val subtype = ct.contentSubtype

    return when {
        isPoiDetailApi(path) -> null
        path.startsWith("/api/") ->
            CachingOptions(
                CacheControl
                    .NoStore(null),
            )
        type == "text" && subtype == "html" ->
            CachingOptions(
                CacheControl
                    .NoCache(null),
            )
        (type == "text" || type == "application") && subtype == "javascript" ->
            CachingOptions(
                CacheControl
                    .NoCache(null),
            )
        type == "text" && subtype == "css" ->
            CachingOptions(
                CacheControl
                    .NoCache(null),
            )
        type == "application" && (subtype == "json" || subtype == "geo+json") ->
            CachingOptions(
                CacheControl
                    .MaxAge(86400),
            )
        type == "image" ->
            CachingOptions(
                CacheControl
                    .MaxAge(86400),
            )
        else -> null
    }
}

private fun isPoiDetailApi(path: String): Boolean =
    if (!path.startsWith("/api/pois/")) {
        false
    } else {
        val id = path.removePrefix("/api/pois/")
        id.isNotEmpty() && id.all(Char::isDigit)
    }
