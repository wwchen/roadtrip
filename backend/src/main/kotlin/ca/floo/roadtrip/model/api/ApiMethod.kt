package ca.floo.roadtrip.model.api

/**
 * The verbs [ApiContract] uses. `model/` never names Ktor (`LayeringGuardTest`),
 * so the contract cannot spell `HttpMethod`; [wireValue] is the same string Ktor
 * puts in `HttpMethodRouteSelector.method.value`.
 */
enum class ApiMethod(
    val wireValue: String,
) {
    GET("GET"),
    POST("POST"),
    PUT("PUT"),
    DELETE("DELETE"),
}
