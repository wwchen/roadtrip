package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.model.api.campground.CampgroundDetailsRequestDto
import ca.floo.roadtrip.model.api.campground.CampgroundDetailsResponseDto
import ca.floo.roadtrip.model.api.campground.CampgroundSearchRequestDto
import ca.floo.roadtrip.model.api.campground.CampgroundSearchResponseDto
import ca.floo.roadtrip.model.api.poi.OnRouteRequestDto
import ca.floo.roadtrip.model.api.poi.PoiDetailFeatureSchema
import ca.floo.roadtrip.model.api.poi.PoiFeatureCollectionSchema
import ca.floo.roadtrip.model.api.poi.PoiSearchResponseSchema
import ca.floo.roadtrip.model.api.poi.PoisOnRouteResponseSchema
import ca.floo.roadtrip.model.api.poi.PoisRequestSchema
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import kotlin.reflect.KClass

/**
 * One body a route can serialize, and the status it serves it at.
 *
 * [body] is null only where the answer carries none: a 204, or the empty text
 * the Slack webhook acks with.
 */
data class ApiBody(
    val status: Int,
    val body: KClass<*>? = null,
)

/**
 * One HTTP endpoint, as data.
 *
 * [success] is the 2xx the route answers with; every other body it can
 * serialize is an [errors] entry, one per (status, class). The generator walks
 * both the same way, so a status-dependent body still reaches TypeScript, and
 * the OpenAPI document builder turns the same rows into per-operation
 * `responses`.
 *
 * 401 and 403 are normally *absent*: the document builder adds them from the
 * route's declared `RouteAccess`, which the routing tree already knows. A row
 * states one only where the handler itself writes it on a route whose access
 * level supplies neither — the `UserOrCapability` watch routes, the anonymous
 * `password/complete`, and the two rows whose refusal vocabulary is richer than
 * their access level.
 *
 * [errors] has no default. It once defaulted to `400 ApiErrorSchema`, which meant
 * a row acquired a published 400 by saying nothing at all — and the contract is
 * one-directional, so nothing could see an error the route cannot serve. Every
 * row now states its errors, `emptyList()` included, read off the handler.
 *
 * [requestRequired] is what the document publishes as `requestBody.required`. It
 * is true for almost every row; `false` belongs to a handler that reads the body
 * through `decodeOptionalTextJsonBody` and answers a blank one with a default.
 *
 * [conditional] marks a route mounted only under some configuration, which the
 * boot guard must not require and the document builder skips when absent.
 */
data class ApiEndpoint(
    val method: ApiMethod,
    val path: String,
    val request: KClass<*>? = null,
    val success: ApiBody,
    val errors: List<ApiBody>,
    val requestRequired: Boolean = true,
    val conditional: Boolean = false,
)

/** [ApiErrorSchema] at each of [statuses] — the shape most rows need. */
fun apiErrors(vararg statuses: Int): List<ApiBody> = statuses.map { ApiBody(it, ApiErrorSchema::class) }

/**
 * What this endpoint declares at [status], success or error; null when it
 * declares nothing there. A top-level extension rather than a member because
 * detekt's `DataClassContainsFunctions` allows only `to`/`as` prefixes on a data
 * class.
 */
fun ApiEndpoint.bodyAt(status: Int): ApiBody? = (listOf(success) + errors).firstOrNull { it.status == status }

/**
 * What the access guard itself answers at this level, before any handler runs,
 * and what a row must therefore not restate. [RouteAccess.User] can only ever
 * refuse with 401 — `check` reaches `Forbidden` only where a role is required —
 * and [RouteAccess.HasRole] with either. [RouteAccess.Anonymous],
 * [RouteAccess.Signed] and [RouteAccess.UserOrCapability] refuse nobody at that
 * layer, so whatever their handlers answer stays on the row.
 *
 * One enumeration, two readers: the OpenAPI document builder publishes these
 * responses on every operation, and the body check behind every route test
 * accepts them. A level added to the sealed type is a compile error here rather
 * than a silently missing 401 in both.
 */
fun RouteAccess.guardBodies(): List<ApiBody> =
    when (this) {
        RouteAccess.User -> listOf(ApiBody(HTTP_UNAUTHORIZED, ApiErrorSchema::class))
        is RouteAccess.HasRole ->
            listOf(
                ApiBody(HTTP_UNAUTHORIZED, ApiErrorSchema::class),
                ApiBody(HTTP_FORBIDDEN, ApiErrorSchema::class),
            )
        RouteAccess.Anonymous, RouteAccess.Signed, RouteAccess.UserOrCapability -> emptyList()
    }

/**
 * Every inhabitant of the sealed type: the four levels that carry no data, and
 * one [RouteAccess.HasRole] per [Role]. Enumerated rather than sampled, so
 * [guardBodyClasses] cannot miss a level.
 */
private val everyAccessLevel: List<RouteAccess> =
    listOf(RouteAccess.Anonymous, RouteAccess.Signed, RouteAccess.User, RouteAccess.UserOrCapability) +
        Role.entries.map(RouteAccess::HasRole)

/**
 * Every class an access guard can answer with, read off [guardBodies] rather
 * than listed a second time.
 *
 * The type generator claims a name for each in the same walk the rows come from,
 * so the OpenAPI document's guard responses reference a name the collision map
 * approved instead of one re-derived at the reference site.
 */
val guardBodyClasses: List<KClass<*>> =
    everyAccessLevel
        .flatMap { it.guardBodies() }
        .mapNotNull { it.body }
        .distinct()

/**
 * Every endpoint the backend serves beneath the `/api/` and `/auth/password/`
 * prefixes, with the status of every body each one can serve.
 *
 * The single source of truth for the generated TypeScript
 * (`frontend/src/api/generated/api-types.ts`), for `components/schemas` and the
 * per-operation bodies of `/api/docs/openapi.json`, for the body check behind
 * every route test, and — via the boot guard in `registerKoinRoutes` — for the
 * routing tree itself: a route with no row here fails the boot, and a row with
 * no route does too.
 *
 * Each path is spelled exactly as `RoutingNode.path(OpenApiRoutePathFormat)`
 * renders the mounted route — `{id}` for a parameter, no trailing slash — since
 * that rendering is what the boot guard, the document builder and the body check
 * all compare against.
 *
 * The whole `/api/docs/` subtree is exempt: framework-generated Swagger assets,
 * plus `openapi.json`, which answers with the framework's own `OpenApiDoc` — a
 * type no DTO of ours describes, and one this package may not even name.
 * `/auth/login`, `/auth/callback` and `/auth/logout` redirect and carry no body.
 */
object ApiContract {
    /**
     * `SettingsErrorResponses.respondSettingsError` is one mapper over one sealed
     * `SettingsError`, installed as a `StatusPages` handler, so any settings
     * handler can reach any of its four statuses. Splitting the set per row would
     * be a claim about which error each service can raise that nothing checks.
     */
    private val settingsErrors =
        apiErrors(HTTP_BAD_REQUEST, HTTP_CONFLICT, HTTP_BAD_GATEWAY, HTTP_SERVICE_UNAVAILABLE)

    @Suppress("LongMethod")
    val endpoints: List<ApiEndpoint> =
        listOf(
            ApiEndpoint(
                ApiMethod.POST,
                "/auth/password/begin",
                PasswordBeginRequestDto::class,
                ApiBody(HTTP_OK, PasswordBeginResponseDto::class),
                apiErrors(HTTP_BAD_REQUEST, HTTP_BAD_GATEWAY, HTTP_SERVICE_UNAVAILABLE),
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/auth/password/complete",
                PasswordCompleteRequestDto::class,
                ApiBody(HTTP_NO_CONTENT),
                apiErrors(HTTP_BAD_REQUEST, HTTP_UNAUTHORIZED, HTTP_SERVICE_UNAVAILABLE),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/me",
                success = ApiBody(HTTP_OK, MeResponseDto::class),
                // AuthRoutes.kt:193 answers one of three MeResponseDto shapes and reads
                // no parameter and no body: nothing it does can reach a 400.
                errors = emptyList(),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/settings",
                success = ApiBody(HTTP_OK, SettingsResponseDto::class),
                errors = settingsErrors,
            ),
            ApiEndpoint(
                ApiMethod.PUT,
                "/api/settings/profile",
                UpdateProfileRequest::class,
                ApiBody(HTTP_OK, SettingsResponseDto::class),
                settingsErrors,
            ),
            ApiEndpoint(
                ApiMethod.PUT,
                "/api/settings/notifications",
                UpdateNotificationsRequest::class,
                ApiBody(HTTP_OK, SettingsResponseDto::class),
                settingsErrors,
                // SettingsRoutes.kt:65 decodes through decodeOptionalTextJsonBody and
                // answers a blank body with UpdateNotificationsRequest().
                requestRequired = false,
            ),
            ApiEndpoint(
                ApiMethod.DELETE,
                "/api/settings/notifications/slack",
                success = ApiBody(HTTP_OK, SettingsResponseDto::class),
                errors = settingsErrors,
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/settings/notifications/slack/test",
                SlackTestRequest::class,
                ApiBody(HTTP_OK, SlackTestResponseDto::class),
                settingsErrors,
                // SettingsRoutes.kt:89, the same optional-body decode: an absent body is
                // SlackTestRequest(), which is the "test the stored webhook" call.
                requestRequired = false,
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/settings/notifications/email/test",
                success = ApiBody(HTTP_OK, EmailTestResponseDto::class),
                errors = settingsErrors,
            ),
            ApiEndpoint(
                ApiMethod.PUT,
                "/api/settings/recgov",
                UpdateRecgovRequest::class,
                ApiBody(HTTP_OK, BookingSettingsDto::class),
                settingsErrors,
            ),
            ApiEndpoint(
                ApiMethod.DELETE,
                "/api/settings/recgov",
                success = ApiBody(HTTP_OK, RecgovRemovedDto::class),
                errors = settingsErrors,
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/settings/recgov/login",
                success = ApiBody(HTTP_OK, RecgovLoginResponseDto::class),
                errors = settingsErrors,
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/settings/recgov/login/mfa",
                RecgovMfaRequest::class,
                ApiBody(HTTP_OK, RecgovLoginResponseDto::class),
                settingsErrors,
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/settings/recgov/verify",
                success = ApiBody(HTTP_OK, RecgovVerifyResponseDto::class),
                errors = settingsErrors,
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/settings/recgov/status",
                success = ApiBody(HTTP_OK, RecgovStatusDto::class),
                errors = settingsErrors,
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/booking/add-to-cart",
                AddToCartRequestDto::class,
                ApiBody(HTTP_OK, AddToCartResponseDto::class),
                // The gates each get their own status so the frontend can say what blocked
                // the hold; 403 is on the row because RouteAccess.User supplies only 401.
                apiErrors(
                    HTTP_BAD_REQUEST,
                    HTTP_FORBIDDEN,
                    HTTP_CONFLICT,
                    HTTP_UNPROCESSABLE_ENTITY,
                    HTTP_BAD_GATEWAY,
                ),
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/pois",
                PoisRequestSchema::class,
                ApiBody(HTTP_OK, PoiFeatureCollectionSchema::class),
                // PoiRoutes.kt:57 answers an unparseable body or an out-of-range bbox.
                apiErrors(HTTP_BAD_REQUEST),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/pois/search",
                success = ApiBody(HTTP_OK, PoiSearchResponseSchema::class),
                // PoiRoutes.kt:89 reads q, limit and categories through helpers that
                // coerce rather than refuse; a missing or malformed one is not a 400.
                errors = emptyList(),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/pois/{id}",
                success = ApiBody(HTTP_OK, PoiDetailFeatureSchema::class),
                errors = apiErrors(HTTP_BAD_REQUEST, HTTP_NOT_FOUND),
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/pois/on-route",
                OnRouteRequestDto::class,
                ApiBody(HTTP_OK, PoisOnRouteResponseSchema::class),
                apiErrors(HTTP_BAD_REQUEST, HTTP_SERVICE_UNAVAILABLE),
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/pois/availability/bulk",
                BulkAvailabilityRequestDto::class,
                ApiBody(HTTP_OK, BulkAvailabilityResponseDto::class),
                listOf(
                    ApiBody(HTTP_BAD_REQUEST, AvailabilityErrorDto::class),
                    ApiBody(HTTP_SERVICE_UNAVAILABLE, AvailabilityErrorDto::class),
                ),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/pois/{id}/campsites",
                success = ApiBody(HTTP_OK, PoiCampsitesResponseSchema::class),
                errors = apiErrors(HTTP_BAD_REQUEST, HTTP_NOT_FOUND),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/pois/{id}/campsites/availability",
                success = ApiBody(HTTP_OK, PoiCampsitesAvailabilityResponseDto::class),
                errors =
                    listOf(
                        HTTP_BAD_REQUEST,
                        HTTP_NOT_FOUND,
                        HTTP_INTERNAL_SERVER_ERROR,
                        HTTP_NOT_IMPLEMENTED,
                        HTTP_SERVICE_UNAVAILABLE,
                    ).map { ApiBody(it, AvailabilityErrorDto::class) },
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/campgrounds/search",
                CampgroundSearchRequestDto::class,
                ApiBody(HTTP_OK, CampgroundSearchResponseDto::class),
                // CampgroundRoutes.kt: an unparseable body, a missing or non-polygon boundary,
                // or an unknown site_type / amenity.
                apiErrors(HTTP_BAD_REQUEST),
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/campgrounds/details",
                CampgroundDetailsRequestDto::class,
                ApiBody(HTTP_OK, CampgroundDetailsResponseDto::class),
                // CampgroundRoutes.kt: an unparseable body or more ids than max-detail-ids.
                apiErrors(HTTP_BAD_REQUEST),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/watches",
                success = ApiBody(HTTP_OK, AvailabilityWatchListResponse::class),
                // AvailabilityWatchRoutes.kt:49 refuses a ?status= outside the vocabulary.
                errors = apiErrors(HTTP_BAD_REQUEST),
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/watches",
                AvailabilityWatchCreateRequest::class,
                ApiBody(HTTP_CREATED, AvailabilityWatchResponse::class),
                apiErrors(HTTP_BAD_REQUEST, HTTP_FORBIDDEN, HTTP_NOT_FOUND, HTTP_INTERNAL_SERVER_ERROR),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/watches/{id}",
                success = ApiBody(HTTP_OK, AvailabilityWatchResponse::class),
                errors = watchRefusals,
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/watches/{id}/modify",
                AvailabilityWatchUpdateRequest::class,
                ApiBody(HTTP_OK, AvailabilityWatchResponse::class),
                watchRefusals,
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/watches/{id}/delete",
                success = ApiBody(HTTP_NO_CONTENT),
                errors = watchRefusals,
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/availability/pollers",
                success = ApiBody(HTTP_OK, AvailabilityPollersListResponse::class),
                // AvailabilityDashboardRoutes.kt:42 refuses an ?active= that is neither
                // true nor false; limit and offset are coerced, not refused.
                errors = apiErrors(HTTP_BAD_REQUEST),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/availability/pollers/summary",
                success = ApiBody(HTTP_OK, AvailabilityPollersSummary::class),
                // AvailabilityDashboardRoutes.kt:58 reads nothing off the call.
                errors = emptyList(),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/availability/pollers/{id}/runs",
                success = ApiBody(HTTP_OK, AvailabilityRunsListResponse::class),
                // AvailabilityDashboardRoutes.kt:67 refuses an unparseable {id}.
                errors = apiErrors(HTTP_BAD_REQUEST),
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/availability/pollers/{id}/force",
                success = ApiBody(HTTP_OK, CheckNowResponseDto::class),
                errors =
                    apiErrors(HTTP_BAD_REQUEST, HTTP_NOT_FOUND) +
                        ApiBody(HTTP_TOO_MANY_REQUESTS, CheckNowCooldownDto::class),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/availability/runs",
                success = ApiBody(HTTP_OK, AvailabilityRunsListResponse::class),
                // AvailabilityDashboardRoutes.kt:92 parses every filter leniently — an
                // unreadable ?since= or ?poller_id= is dropped, not refused.
                errors = emptyList(),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/availability/changes",
                success = ApiBody(HTTP_OK, ListAvailabilityChangesResponse::class),
                errors = apiErrors(HTTP_BAD_REQUEST, HTTP_NOT_FOUND),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/availability/changes/summary",
                success = ApiBody(HTTP_OK, AvailabilitySnapshotsSummaryResponse::class),
                errors = apiErrors(HTTP_BAD_REQUEST, HTTP_NOT_FOUND),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/route",
                success = ApiBody(HTTP_OK, RouteFeatureCollectionDto::class),
                errors =
                    listOf(
                        ApiBody(HTTP_BAD_REQUEST, RouteErrorDto::class),
                        ApiBody(HTTP_SERVICE_UNAVAILABLE, RouteErrorDto::class),
                    ),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/geocode",
                success = ApiBody(HTTP_OK, GeocodeResponseDto::class),
                errors = apiErrors(HTTP_BAD_REQUEST, HTTP_SERVICE_UNAVAILABLE),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/build-info",
                success = ApiBody(HTTP_OK, BuildInfoDto::class),
                // BuildInfoRoutes.kt:15 reads three config strings and responds. No body,
                // no parameter, no throw: no StatusPages mapping can fire on it.
                errors = emptyList(),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/health",
                success = ApiBody(HTTP_OK, HealthResponseDto::class),
                // HealthRoutes.kt:32 is the liveness probe: a clock read and a DTO.
                errors = emptyList(),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/health/ready",
                // The readiness probe answers the same DTO at both statuses: 503 is the
                // load balancer's signal, not a different shape. HealthRoutes.kt:37 reads
                // nothing off the call, so 503 is the only non-2xx it can reach.
                success = ApiBody(HTTP_OK, ReadinessResponseDto::class),
                errors = listOf(ApiBody(HTTP_SERVICE_UNAVAILABLE, ReadinessResponseDto::class)),
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/admin/data/import",
                success = ApiBody(HTTP_OK, FanOutResponseSchema::class),
                errors =
                    apiErrors(HTTP_BAD_REQUEST) +
                        ApiBody(HTTP_INTERNAL_SERVER_ERROR, FanOutResponseSchema::class),
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/admin/data/import/{target}",
                success = ApiBody(HTTP_OK, RunOutcomeSchema::class),
                errors =
                    apiErrors(HTTP_BAD_REQUEST) +
                        listOf(
                            ApiBody(HTTP_NOT_FOUND, ErrorUnknownTargetSchema::class),
                            ApiBody(HTTP_CONFLICT, ErrorTargetBusySchema::class),
                            ApiBody(HTTP_INTERNAL_SERVER_ERROR, RunOutcomeSchema::class),
                        ),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/admin/data/runs",
                success = ApiBody(HTTP_OK, RunsListSchema::class),
                // AdminIngestRoutes.kt:74 passes ?target= straight through as a filter.
                errors = emptyList(),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/admin/data/runs/{id}",
                success = ApiBody(HTTP_OK, RunDetailSchema::class),
                // An unparseable id is a 400 carrying the same shape as the 404, because
                // `longPath` answers null rather than throwing into StatusPages.
                errors =
                    listOf(
                        ApiBody(HTTP_BAD_REQUEST, ErrorNotFoundSchema::class),
                        ApiBody(HTTP_NOT_FOUND, ErrorNotFoundSchema::class),
                    ),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/admin/data/status",
                success = ApiBody(HTTP_OK, StatusResponseSchema::class),
                // AdminIngestRoutes.kt:96 reads nothing off the call.
                errors = emptyList(),
            ),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/slack/interactivity",
                // Every answer is an empty text/plain body Slack only reads the status of.
                success = ApiBody(HTTP_OK),
                errors = emptyList(),
                conditional = true,
            ),
        )

    /** `(method, path)` for every row. */
    fun keys(): Set<Pair<String, String>> = endpoints.mapTo(LinkedHashSet()) { it.method.wireValue to it.path }

    /** The rows a boot must actually have mounted — every row that is not [ApiEndpoint.conditional]. */
    fun requiredKeys(): Set<Pair<String, String>> =
        endpoints.filterNot { it.conditional }.mapTo(LinkedHashSet()) { it.method.wireValue to it.path }
}

/**
 * What `AvailabilityWatchRoutes.respondFailure` can answer for a `UserOrCapability`
 * route: the level refuses nobody, so the handler resolves the magic-link token
 * itself and writes the 401 and the 403.
 */
private val watchRefusals =
    apiErrors(
        HTTP_BAD_REQUEST,
        HTTP_UNAUTHORIZED,
        HTTP_FORBIDDEN,
        HTTP_NOT_FOUND,
        HTTP_INTERNAL_SERVER_ERROR,
    )
