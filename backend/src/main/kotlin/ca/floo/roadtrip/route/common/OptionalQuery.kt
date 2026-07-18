package ca.floo.roadtrip.route.common

internal sealed interface OptionalQuery<out T> {
    data object Missing : OptionalQuery<Nothing>

    data class Parsed<T>(
        val value: T,
    ) : OptionalQuery<T>

    data class Invalid(
        val rawValue: String,
    ) : OptionalQuery<Nothing>
}
