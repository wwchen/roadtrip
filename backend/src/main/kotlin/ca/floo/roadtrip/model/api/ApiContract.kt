package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.model.api.poi.OnRouteRequestDto
import ca.floo.roadtrip.model.api.poi.PoiDetailFeatureSchema
import ca.floo.roadtrip.model.api.poi.PoiFeatureCollectionSchema
import ca.floo.roadtrip.model.api.poi.PoiSearchResponseSchema
import ca.floo.roadtrip.model.api.poi.PoisOnRouteResponseSchema
import ca.floo.roadtrip.model.api.poi.PoisRequestSchema
import kotlin.reflect.KClass

/**
 * One HTTP endpoint, as data.
 *
 * [response] is the 2xx body; every other body the route can serialize — a 429
 * cooldown, a typed availability refusal — is an [errors] entry. The generator
 * walks both the same way, so a status-dependent body still reaches TypeScript.
 * [conditional] marks a route mounted only under some configuration, which the
 * boot guard must not require.
 */
data class ApiEndpoint(
    val method: ApiMethod,
    val path: String,
    val request: KClass<*>? = null,
    val response: KClass<*>? = null,
    val errors: List<KClass<*>> = listOf(ApiErrorSchema::class),
    val conditional: Boolean = false,
)

/**
 * Every endpoint the backend serves beneath the `/api/` and `/auth/password/`
 * prefixes.
 *
 * The single source of truth for the generated TypeScript
 * (`frontend/src/api/generated/api-types.ts`) and, via the boot guard in
 * `registerKoinRoutes`, for the routing tree itself: a route with no row here
 * fails the boot, and a row with no route does too.
 *
 * Each path is spelled exactly as `RoutingNode.path(OpenApiRoutePathFormat)`
 * renders the mounted route — `{id}` for a parameter, no trailing slash — since
 * that rendering is what the boot guard compares against.
 *
 * The whole `/api/docs/` subtree is exempt: framework-generated Swagger assets,
 * plus `openapi.json`, which answers with the framework's own `OpenApiDoc` — a
 * type no DTO of ours describes, and one this package may not even name.
 * `/auth/login`, `/auth/callback` and `/auth/logout` redirect and carry no body.
 */
object ApiContract {
    @Suppress("LongMethod")
    val endpoints: List<ApiEndpoint> =
        listOf(
            ApiEndpoint(ApiMethod.POST, "/auth/password/begin", PasswordBeginRequestDto::class, PasswordBeginResponseDto::class),
            ApiEndpoint(ApiMethod.POST, "/auth/password/complete", PasswordCompleteRequestDto::class),
            ApiEndpoint(ApiMethod.GET, "/api/me", response = MeResponseDto::class),
            ApiEndpoint(ApiMethod.GET, "/api/settings", response = SettingsResponseDto::class),
            ApiEndpoint(ApiMethod.PUT, "/api/settings/profile", UpdateProfileRequest::class, SettingsResponseDto::class),
            ApiEndpoint(
                ApiMethod.PUT,
                "/api/settings/notifications",
                UpdateNotificationsRequest::class,
                SettingsResponseDto::class,
            ),
            ApiEndpoint(ApiMethod.DELETE, "/api/settings/notifications/slack", response = SettingsResponseDto::class),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/settings/notifications/slack/test",
                SlackTestRequest::class,
                SlackTestResponseDto::class,
            ),
            ApiEndpoint(ApiMethod.POST, "/api/settings/notifications/email/test", response = EmailTestResponseDto::class),
            ApiEndpoint(ApiMethod.PUT, "/api/settings/recgov", UpdateRecgovRequest::class, BookingSettingsDto::class),
            ApiEndpoint(ApiMethod.DELETE, "/api/settings/recgov", response = RecgovRemovedDto::class),
            ApiEndpoint(ApiMethod.POST, "/api/settings/recgov/login", response = RecgovLoginResponseDto::class),
            ApiEndpoint(ApiMethod.POST, "/api/settings/recgov/login/mfa", RecgovMfaRequest::class, RecgovLoginResponseDto::class),
            ApiEndpoint(ApiMethod.POST, "/api/settings/recgov/verify", response = RecgovVerifyResponseDto::class),
            ApiEndpoint(ApiMethod.GET, "/api/settings/recgov/status", response = RecgovStatusDto::class),
            ApiEndpoint(ApiMethod.POST, "/api/booking/add-to-cart", AddToCartRequestDto::class, AddToCartResponseDto::class),
            ApiEndpoint(ApiMethod.POST, "/api/pois", PoisRequestSchema::class, PoiFeatureCollectionSchema::class),
            ApiEndpoint(ApiMethod.GET, "/api/pois/search", response = PoiSearchResponseSchema::class),
            ApiEndpoint(ApiMethod.GET, "/api/pois/{id}", response = PoiDetailFeatureSchema::class),
            ApiEndpoint(ApiMethod.POST, "/api/pois/on-route", OnRouteRequestDto::class, PoisOnRouteResponseSchema::class),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/pois/availability/bulk",
                BulkAvailabilityRequestDto::class,
                BulkAvailabilityResponseDto::class,
                errors = listOf(AvailabilityErrorDto::class, ApiErrorSchema::class),
            ),
            ApiEndpoint(ApiMethod.GET, "/api/pois/{id}/campsites", response = PoiCampsitesResponseSchema::class),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/pois/{id}/campsites/availability",
                response = PoiCampsitesAvailabilityResponseDto::class,
                errors = listOf(AvailabilityErrorDto::class, ApiErrorSchema::class),
            ),
            ApiEndpoint(ApiMethod.GET, "/api/watches", response = AvailabilityWatchListResponse::class),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/watches",
                AvailabilityWatchCreateRequest::class,
                AvailabilityWatchResponse::class,
            ),
            ApiEndpoint(ApiMethod.GET, "/api/watches/{id}", response = AvailabilityWatchResponse::class),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/watches/{id}/modify",
                AvailabilityWatchUpdateRequest::class,
                AvailabilityWatchResponse::class,
            ),
            ApiEndpoint(ApiMethod.POST, "/api/watches/{id}/delete"),
            ApiEndpoint(ApiMethod.GET, "/api/availability/pollers", response = AvailabilityPollersListResponse::class),
            ApiEndpoint(ApiMethod.GET, "/api/availability/pollers/summary", response = AvailabilityPollersSummary::class),
            ApiEndpoint(ApiMethod.GET, "/api/availability/pollers/{id}/runs", response = AvailabilityRunsListResponse::class),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/availability/pollers/{id}/force",
                response = CheckNowResponseDto::class,
                errors = listOf(CheckNowCooldownDto::class, ApiErrorSchema::class),
            ),
            ApiEndpoint(ApiMethod.GET, "/api/availability/runs", response = AvailabilityRunsListResponse::class),
            ApiEndpoint(ApiMethod.GET, "/api/availability/changes", response = ListAvailabilityChangesResponse::class),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/availability/changes/summary",
                response = AvailabilitySnapshotsSummaryResponse::class,
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/route",
                response = RouteFeatureCollectionDto::class,
                errors = listOf(RouteErrorDto::class, ApiErrorSchema::class),
            ),
            ApiEndpoint(ApiMethod.GET, "/api/geocode", response = GeocodeResponseDto::class),
            ApiEndpoint(ApiMethod.GET, "/api/build-info", response = BuildInfoDto::class),
            ApiEndpoint(ApiMethod.GET, "/api/health", response = HealthResponseDto::class),
            ApiEndpoint(ApiMethod.GET, "/api/health/ready", response = ReadinessResponseDto::class),
            ApiEndpoint(ApiMethod.POST, "/api/admin/data/import", response = FanOutResponseSchema::class),
            ApiEndpoint(
                ApiMethod.POST,
                "/api/admin/data/import/{target}",
                response = RunOutcomeSchema::class,
                errors = listOf(ErrorUnknownTargetSchema::class, ErrorTargetBusySchema::class, ApiErrorSchema::class),
            ),
            ApiEndpoint(ApiMethod.GET, "/api/admin/data/runs", response = RunsListSchema::class),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/admin/data/runs/{id}",
                response = RunDetailSchema::class,
                errors = listOf(ErrorNotFoundSchema::class, ApiErrorSchema::class),
            ),
            ApiEndpoint(ApiMethod.GET, "/api/admin/data/status", response = StatusResponseSchema::class),
            ApiEndpoint(ApiMethod.POST, "/api/slack/interactivity", conditional = true),
        )

    /** `(method, path)` for every row. */
    fun keys(): Set<Pair<String, String>> = endpoints.mapTo(LinkedHashSet()) { it.method.wireValue to it.path }

    /** The rows a boot must actually have mounted — every row that is not [ApiEndpoint.conditional]. */
    fun requiredKeys(): Set<Pair<String, String>> =
        endpoints.filterNot { it.conditional }.mapTo(LinkedHashSet()) { it.method.wireValue to it.path }
}
