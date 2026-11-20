package ca.floo.roadtrip.route.internal

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

const val INTERNAL_API_VERSION_HEADER = "X-Internal-Api-Version"
internal const val INTERNAL_VERSION_UNSUPPORTED = "internal_version_unsupported"
private const val VERSION_OF_A_CALLER_THAT_NAMES_NONE = 1
private val servedInternalApiVersions = setOf(1)

internal val servedInternalApiVersionsDetail = "served versions: ${servedInternalApiVersions.joinToString()}"

internal fun ApplicationCall.speaksServedInternalApiVersion(): Boolean {
    val named = request.header(INTERNAL_API_VERSION_HEADER) ?: return VERSION_OF_A_CALLER_THAT_NAMES_NONE in servedInternalApiVersions
    return named.trim().toIntOrNull() in servedInternalApiVersions
}
