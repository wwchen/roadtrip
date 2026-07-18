package ca.floo.roadtrip.route.common

internal sealed interface RouteBodyResult<out T> {
    data class Valid<T>(
        val value: T,
    ) : RouteBodyResult<T>

    data class Invalid(
        val detail: String?,
    ) : RouteBodyResult<Nothing>
}
