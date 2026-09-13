package ca.floo.roadtrip.model.api

/**
 * The HTTP statuses this codebase names, in the one package `model/` may always
 * import.
 *
 * [ApiContract]'s rows are the first reader; the second is the provider layer,
 * which classifies an *upstream* vendor's status
 * (`service/availability/provider/ProviderUpstreamErrors.kt` and the adapters it
 * serves). Both used to carry their own copies of the same literals.
 *
 * `model/` never names Ktor (`LayeringGuardTest`), so `HttpStatusCode.Created.value`
 * is unavailable where the rows are written, and a bare `201` at a call site is
 * the inline magic constant `AGENTS.md` forbids. Same reason [ApiMethod] exists.
 * Top level and same-package, so the rows need no import; the `HTTP_` prefix
 * keeps them clear of everything else under `model/api/`.
 */
const val HTTP_OK = 200
const val HTTP_CREATED = 201
const val HTTP_NO_CONTENT = 204
const val HTTP_BAD_REQUEST = 400
const val HTTP_UNAUTHORIZED = 401
const val HTTP_FORBIDDEN = 403
const val HTTP_NOT_FOUND = 404
const val HTTP_CONFLICT = 409
const val HTTP_UNPROCESSABLE_ENTITY = 422
const val HTTP_TOO_MANY_REQUESTS = 429
const val HTTP_INTERNAL_SERVER_ERROR = 500
const val HTTP_NOT_IMPLEMENTED = 501
const val HTTP_BAD_GATEWAY = 502
const val HTTP_SERVICE_UNAVAILABLE = 503
