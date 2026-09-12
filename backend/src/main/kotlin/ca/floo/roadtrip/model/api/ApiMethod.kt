package ca.floo.roadtrip.model.api

/**
 * The verbs [ApiContract] uses. `model/` never names Ktor (`LayeringGuardTest`),
 * so the contract cannot spell `HttpMethod`; [wireValue] is the same string Ktor
 * puts in `HttpMethodRouteSelector.method.value`.
 *
 * A closed vocabulary: a route mounted with a verb that is not here cannot be
 * given a contract row at all, so the verb is added here first. `PATCH`, `HEAD`
 * and `OPTIONS` are declared ahead of their first route for exactly that reason
 * — a `head`/`options` leaf added for a CORS preflight or a cache probe would
 * otherwise fail the boot with nowhere to write the row.
 */
enum class ApiMethod(
    val wireValue: String,
) {
    GET("GET"),
    POST("POST"),
    PUT("PUT"),
    PATCH("PATCH"),
    DELETE("DELETE"),
    HEAD("HEAD"),
    OPTIONS("OPTIONS"),
}
