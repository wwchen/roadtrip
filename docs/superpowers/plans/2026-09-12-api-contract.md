# API Contract: Generated from the DTOs, Checked in CI — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Kotlin `@Serializable` DTOs are the single source of truth for the wire contract: `ApiContract` declares every endpoint as data, a generator inside the backend build emits `frontend/src/api/generated/api-types.ts` from the serialization descriptors honouring the one encoder's actual behaviour, the committed file is checked in CI, and the frontend imports it instead of hand-maintaining seventy-nine mirrors.

**Architecture:** `model/api/ApiContract.kt` lists 46 `ApiEndpoint` rows (`ApiMethod`, path, request `KClass`, response `KClass`, error `KClass`es). A boot guard in `registerKoinRoutes` compares the live routing tree against that list in both directions, the way RFC 0010's access guard already does. `ca.floo.roadtrip.apigen` walks `serializer(kclass).descriptor` transitively from every contract row and writes the TypeScript; `:backend:generateApiTypes` writes the committed file and `:backend:checkApiTypes` fails when it drifts. Four string vocabularies become `@Serializable` enums so their TS unions are generated too, and `V63` records *why* a watch is done instead of leaving the frontend to guess.

**Tech Stack:** Kotlin 2.4.10 / Ktor 3.5.2 / kotlinx-serialization-json 1.11.0 / Gradle (`JavaExec`) / jOOQ + Postgres (Flyway, Testcontainers via `SharedDbTest`) / JUnit 5 + kotlin.test / TypeScript 7 + vitest 4.

**Spec:** `docs/superpowers/specs/2026-09-12-api-contract-design.md`. Audit finding 14 in `docs/superpowers/specs/2026-09-09-architecture-audit.md`. Issue #743. Follows phase 5a (`docs/superpowers/plans/2026-09-11-backend-seams.md`) and 5b (`docs/superpowers/plans/2026-09-11-geometry-policy.md`).

## Resolutions

Where the spec left a choice, or its letter fought what is actually in the tree, this plan decided. Each decision is load-bearing for the task that implements it.

1. **The generator lives in `main` (`ca.floo.roadtrip.apigen`), not in a separate `apiGen` source set.** The spec asks for a source set that depends on `main`. Twelve DTOs under `model/api/` are `internal` (`ReadinessResponseDto`, `HealthResponseDto`, `GeocodeResponseDto`, `GeocodeResultDto`, `RouteErrorDto`, `RouteFeatureCollectionDto`, `RouteFeatureDto`, `RouteLineGeometryDto`, `RoutePropertiesDto`, `RouteLegDto`, `CorridorFeatureDto`, `CorridorPropertiesDto`), and a second source set is a second compilation unit that cannot see them — `ApiContract.kt` itself would not compile there. The alternatives are widening twelve types to `public` for a build tool's benefit, or wiring an `associateWith` friend compilation. Neither is worth it: a `main()` in `main` plus a `JavaExec` over `sourceSets["main"].runtimeClasspath` gives exactly the two Gradle tasks the spec asks for, and the entry-point file joins `MainKt` in the kover exclude list for the same reason `MainKt` is there. Task 4.
2. **`generateApiTypes` and `checkApiTypes` do transitively need Docker, and that is stated rather than worked around.** `generateSchemaSourceOnCompilation` is `true`, so compiling `main` runs `generateJooq`, which starts a Testcontainers Postgres. Both tasks therefore have exactly the same prerequisite as `:backend:test`. They go in the `backend-tests` CI job (ubuntu-latest, Docker present) and in `make test` (which already requires Docker); they deliberately do **not** go in `gradle-lint`, which exists precisely to run without codegen (`-x :backend:generateJooq`). Tasks 4 and 7.
3. **The contract's authoritative check is a boot guard; `ApiContractCoverageTest` tests the comparator.** The spec asks the test to build "the real routing tree". The real tree needs every controller instance — `CampsiteAvailabilityController` alone pulls in `CampsiteCatalogService`, `CampsiteAvailabilityService`, `FailoverAvailabilityFetcher`, `BookingHorizonResolver`, `WatchCapabilityService` and a tenant registry — and a test that reassembles all of it is a second copy of `RouteModule` that rots. `RouteAccessCoverage.kt` already solved this exact problem and says so in its own KDoc: the live-tree check is the boot guard in `registerKoinRoutes`, and the test exercises the walker. This plan follows that precedent verbatim. The boot guard sees the *actual* tree, including the conditionally-mounted Slack route, which no test mount would; it runs on every `make run`, every deploy, and in CI's `smoke` job, which boots the fat jar against Postgres. Task 1.
4. **`ApiEndpoint.conditional` exists because `/api/slack/interactivity` is mounted only when a Slack signing secret is configured** (`slackInteractivity?.let { … }` in `RouteModule`). Strict bidirectional equality against the live tree would therefore fail on a fresh clone and in CI. The guard asserts: every mounted contracted leaf has a row (no exceptions), and every non-`conditional` row is mounted. Task 1.
5. **`/api/docs/**` is outside the contracted surface.** The spec says the contract covers everything under `/api/**`. `/api/docs` is a framework-generated Swagger UI subtree, and `/api/docs/openapi.json` responds with `io.ktor.openapi.OpenApiDoc` — a Ktor type the generator must never walk and `model/` must never name. The same exemption `RouteAccessCoverage` already carries for the Swagger subtree applies here, extended to `openapi.json`. Task 1.
6. **Three request DTOs move out of route files into `model/api/`, and two dead schemas are deleted.** `PoisOnRouteRoutes.kt` declares `private @Serializable class OnRouteRequestDto` / `WaypointDto`, and `SettingsRoutes.kt` declares `private @Serializable data class SlackTestRequest`. A contract row cannot name a private route-local class, and AGENTS.md already wants request bodies typed in `model/`. They move as-is — same field types, same defaults, so error text and status codes are unchanged; only the `validated(...)` functions stay behind in the route, where their `RouteConfig` dependency belongs. `model/api/poi/PoisOnRouteRequestSchema.kt` and `model/api/poi/WaypointSchema.kt` are referenced by nothing in the tree and describe a stricter shape than the route actually accepts; they are deleted rather than left as a second, wrong answer. Task 1.
7. **`WatchStatus` moves to `model/availability/WatchStatus.kt`.** The spec describes all four vocabularies as `object { const val }`; `WatchStatus` is already an `enum class` with a `wireValue`, but it lives under `service/availability/`, and `model/api/AvailabilityWatchSchema` cannot import `service/` without inverting the layer order the whole audit is about. It moves beside `AvailabilityStatus`, which is the exact precedent: `@Serializable enum class X(val wireValue: String)` with one `@SerialName` per constant. `wireValue` stays — it is what the repos write. Task 2.
8. **`WatchDoneReason` lives at `model/availability/WatchDoneReason.kt`, beside `WatchStatus`.** Same reason, same shape. Task 3.
9. **A class reachable from both a request root and a response root must agree on optionality, or generation fails.** The spec floats a `XDtoInput` variant "only when the two differ", which needs a fixpoint over the reference graph to rename every enclosing type too. Today exactly one class is shared — `AvailabilityWatchTargetSchema`, in `AvailabilityWatchCreateRequest.targets` and `AvailabilityWatchSchema.targets` — and both of its fields are nullable with defaults, so response optionality (`nullable`) and request optionality (`nullable || has default`) give the identical answer. The generator therefore emits one interface per class and throws, naming the class and the disagreeing fields, if a shared class ever stops agreeing. That is the same treatment the spec already gives sealed types and name collisions: a build failure that a human resolves, not a half-built variant nobody uses. Task 4.
10. **A status-dependent second body is an `errors` entry, not a second response class.** `response` is the 2xx success body; everything else the route can serialize goes in `errors`. `POST /api/availability/pollers/{id}/force` is `response = CheckNowResponseDto`, `errors = [CheckNowCooldownDto, ApiErrorSchema]`; the campsite and bulk availability routes carry `AvailabilityErrorDto`; `/api/route` carries `RouteErrorDto`; the admin import routes carry their three typed refusals. Every error class is walked by the generator exactly like a response class, so the frontend gets the type either way, and `API_ENDPOINTS` records only method/path/request/response. Task 1.
11. **`API_ENDPOINTS` is emitted in this phase, unused**, as the spec says — sorted by path then method, `as const`, so a later phase can type the fetch helpers against it without a second generator change. Task 4.
12. **A hand-written TS name equal to its generated name is re-exported, not aliased.** `poi-api.ts` exports `AmenityDto`, `CarrierSignalDto`, `RatingDto`, `PriceDto`, `ScheduleDto`, `AlertDto`; `availability-api.ts` exports `AvailabilityWindowState` and `AddToCartState`; `watches-api.ts` exports `WatchStatus`. `export type X = X;` is circular, so those become `export type { X } from './generated/api-types';`. Every other module keeps its own name with `export type Local = GeneratedDto;`. Importers do not change either way. Tasks 5 and 6.
13. **`campground-detail.ts` keeps its single `typed()` assertion.** The spec says the `p as Partial<CampgroundDetail>` cast "goes because the type now has the fields". It cannot: `flattenHydratedPoi` returns `Record<string, unknown>`, and no widening of `CampgroundDetail` makes an open bag assignable to it. What goes is the *lie* — the alias stops declaring 9 of `PoiCategoryDetailSchema`'s 55 fields and becomes the schema itself, so every drawer reader is typechecked against the server's own field names. `Partial<>` stays, because the generated type has required fields (`sources`, `amenities`, `cell_coverage`, `activities`, `alerts`, `charger_amenities` — all non-null with defaults, hence always emitted) that a flattened bag need not carry. The KDoc is rewritten to say exactly that. Task 6.
14. **`poi-api.ts` keeps its GeoJSON generics.** `PoiPinFeature`/`PoiPinCollection` are `geojson`'s `Feature<Point, P>` / `FeatureCollection<Point, P>`, and MapLibre and the map modules require those types. Only the *properties* type becomes generated (`PoiPinProperties = SlimPoiPropertiesSchema`), plus the search hit, the search response and the campground detail bag. `ViewportPoiCollection extends PoiPinCollection { truncated: boolean }` stays as written; the generated `PoiFeatureCollectionSchema` is not substituted for it. Task 6.
15. **`LayeringGuardTest` gains a fourth case: `model/` never names Ktor.** Today only `service/` is guarded. `ApiMethod` exists so `ApiContract` need not import `HttpMethod`; without a guard nothing stops the next edit from importing it. Task 1.
16. **Typing `AvailabilityWatchUpdateRequest.status` trades one error code for another, deliberately.** `AvailabilityWatchRequestMapper` answers a bad `status` in the body of `POST /api/watches/{id}/modify` with `400 invalid_status` and the detail "status must be active, paused, or done". Once the field is a `WatchStatus`, kotlinx refuses the value at decode time and the route answers `400 invalid_body` instead. Nothing in the repo asserts `invalid_status` from that path — the only test coverage of that code is the `?status=` **query** parameter on `GET /api/watches`, which stays a `String` parse and is untouched — and the generated `UpdateWatchRequest.status?: WatchStatus` stops a client sending a bad value in the first place. Both answers are 400. Task 2.

## Global Constraints

These are the spec's binding rules. Every task's requirements implicitly include this section.

- **The one encoder.** `route/common/RouteResponses.kt` holds the only `Json`: `encodeDefaults = true`, `explicitNulls = false`, `ignoreUnknownKeys = true`. `RoadtripRouting.installRoadtripPlugins` installs *that same instance* into `ContentNegotiation`, so `call.respond(dto)` and `respondEncodedJson(dto)` encode identically. Every optionality rule below is a statement about that instance.
- **Response optionality:** a field is optional (`foo?: T`) **iff it is nullable** — `explicitNulls = false` omits null. A non-null field with a default is **required**: `encodeDefaults = true` always emits it. `| null` is **never** generated.
- **Request optionality:** a field is optional **iff it is nullable or has a default**. kotlinx accepts a missing element when it has a default, and a nullable element without a default is also accepted as absent under `explicitNulls = false`.
- **The type mapping table**, exactly:

  | Kotlin | TypeScript |
  |---|---|
  | `@Serializable` class | `export interface <SimpleName> { ... }`, keys from the descriptor's element names (i.e. `@SerialName`) |
  | `String`, `Char` | `string` |
  | `Int`, `Long`, `Short`, `Byte`, `Float`, `Double` | `number` (ids stay below 2^53; documented) |
  | `Boolean` | `boolean` |
  | `List<T>`, `Set<T>`, arrays | `T[]` |
  | `Map<K, V>` | `Record<string, V>` |
  | `@Serializable enum` | `export type <Name> = 'a' \| 'b' \| ...` from the constants' serial names |
  | `JsonElement` | `unknown` |
  | `JsonObject` | `Record<string, unknown>` |
  | `JsonArray` | `unknown[]` |
  | `JsonPrimitive` | `string \| number \| boolean` |
  | value class / custom serializer | whatever its descriptor's primitive kind says (an `Instant` serialized as a string is `string`) |
  | sealed / polymorphic | **generation fails**, naming the class |

- **Determinism.** Declarations sorted by name; fields in declaration order; `API_ENDPOINTS` sorted by path then method; a header naming the generator and forbidding hand edits; LF line endings; a trailing newline. Two runs over an unchanged tree produce byte-identical output.
- **Generation fails, loudly and by name**, on: a sealed or polymorphic descriptor; a contextual descriptor; two classes with the same simple name and different serial names; a `kotlinx.serialization.json` type other than the four mapped ones; a class reachable from both a request and a response root whose optionality disagrees.
- **`ApiContract` covers `/api/**` and `/auth/password/**`**, minus the `/api/docs/**` exemption of Resolution 5. Redirect-only auth routes (`/auth/login`, `/auth/callback`, `/auth/logout`) are **not** in it — they are outside both prefixes, and the spec's "listed with no bodies" would put them outside the covered surface the guard compares. The coverage check is bidirectional: a mounted contracted leaf with no row fails, and a non-`conditional` row with no mounted leaf fails.
- **The four vocabularies become enums with identical serial names and unchanged persistence.** `WatchStatus` (`active`/`paused`/`done`), `RecgovSessionState` (`not_configured`/`active`/`not_logged_in`/`expired`/`check_failed`/`companion_unavailable`), `RecgovLoginStatus` (`ok`/`mfa_required`/`failed`), `BookingActionStatus` (`completed`). Every `@SerialName` equals today's string exactly. The repos keep reading and writing the same strings. Anything that compared a field to a `const val` compares to the enum constant.
- **`V63__availability_watch_done_reason.sql`**: `ALTER TABLE availability_watch ADD COLUMN done_reason text` with `CHECK (done_reason IS NULL OR done_reason IN ('triggered', 'elapsed'))`. **No backfill** — existing `done` rows stay null. **Never edit an applied migration**; `V62` is the highest today.
- **The frontend keeps its exported alias names** so the 76 importers under `frontend/src/` do not change. Every hand-written mirror interface and every "Mirrors …" comment in `frontend/src/api/` is deleted.
- **`watch-triggers.ts` loses the `triggerConfig` and `stopWhenTriggered` casts**, and the two test cases that manufacture camelCase payloads go with them. The legitimate top-level `channel` legacy read stays.
- **`alert-rows.ts` reads `done_reason`**; the date inference survives only as the fallback for pre-migration rows, with a comment saying exactly that.
- **The gate.** `make test` and `.github/workflows/ci.yml`'s `backend-tests` job run `:backend:checkApiTypes`; the `backend_tests` path filter gains `frontend/src/api/generated/**` so a hand edit to the generated file cannot slip through a frontend-only PR. `make api-types` runs the generator for developers.
- **Docs.** `docs/backend-architecture.md` documents the contract list, the generator, the optionality rules and the "add a route → add a contract row → `make api-types` → commit the diff" loop. `docs/frontend-components.md`'s "closed mirror" paragraph is rewritten. `AGENTS.md` gains one bullet: TS API types are generated; never edit `frontend/src/api/generated/`.
- **Out of scope** (backlog on #743): JSON-Schema `components/schemas` in `/api/docs/openapi.json`; runtime response validation in `http.ts`; adopting or deleting `POST /api/pois/availability/bulk`; a global `JsonNamingStrategy.SnakeCase` in place of the 222 `@SerialName` annotations; the `*Dto` vs `*Schema` naming split; typing the fetch helpers against `API_ENDPOINTS`.
- **Layering rules, verbatim from `AGENTS.md`:**
  - Prefer typed Kotlin/Java DTOs (`@Serializable` data classes or existing schema classes) for request/response bodies. Do not hand-build JSON strings in routes when a DTO can represent the shape.
  - Services take a `UnitOfWork` (when several writes must land together) or the repo handles they use — never a `DSLContext`. `org.jooq` appears only under `repo/`, `db/`, and `di/InfraModule.kt`; `LayeringGuardTest` fails the build otherwise.
  - SQL, jOOQ DSL queries, table references, and persistence mapping belong in `repo` classes only. Routes and services call repo methods rather than embedding SQL.
  - Layering is `routes -> service -> repo`: routes are the HTTP shell and do not add new route-to-repo paths.
  - Keep business logic out of routes; put orchestration in `service` and persistence in `repo`.
  - **No inline magic constants.** Every literal introduced here is a named `const val`: the TypeScript primitive names, the `kotlinx.serialization.json` serial names, the generated-file header, the path prefixes, the done-reason wire strings.
  - **Layered abstractions.** `model/` imports neither Ktor nor jOOQ; `ApiMethod` exists for that reason and `LayeringGuardTest` enforces it from Task 1 on.
  - **No half-finished implementations.** If a method exists, it works.
- **Comments short and rare.** Keep KDoc whose claim stays true; delete the paragraphs this change falsifies (the "One of [RecgovSessionState]" field comments, `alert-rows.doneKind`'s "Inferred rather than read", `poi-api.ts`'s "One mirror per DTO"). Do not narrate the refactor in comments.
- **detekt/ktlint:** a non-`const` private top-level `val` needs `@Suppress("TopLevelPropertyNaming")` (precedent: `PoiRegistry.kt`); `const val` uses `SCREAMING_SNAKE`; lines stay under 140 characters; `ClassOrdering` puts `companion object` last.
- **Backend gate, run at the end of every backend task:** `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q` from the worktree root. Docker must be running (Testcontainers for `SharedDbTest`, and `generateJooq` for the compile).
- **Frontend gate, run at the end of every frontend task:** `cd frontend && npm run typecheck && npm run test && npm run lint`.
- One commit per task, conventional prefix, `Refs #743`, trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- A hook denies the first Write/Edit to each file per session: state the facts it asks for in one line and re-issue the identical call. Never run `git stash`.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `model/api/ApiMethod.kt` (new) | The four verbs, so `model/` need not name Ktor | 1 |
| `model/api/ApiContract.kt` (new) | `ApiEndpoint` + the 46 rows, and the two set views the guard compares | 1 |
| `model/api/SlackTestRequest.kt` (new) | Body of `POST /api/settings/notifications/slack/test`, moved out of the route | 1 |
| `model/api/poi/OnRouteRequestDto.kt` (new) | Body of `POST /api/pois/on-route` + its waypoint, moved out of the route | 1 |
| `model/api/poi/PoisOnRouteRequestSchema.kt`, `model/api/poi/WaypointSchema.kt` (deleted) | Dead, and a stricter shape than the route accepts | 1 |
| `route/common/RouteInventory.kt` (new) | `RouteLeaf`, the method-leaf walk, the contracted-surface filter | 1 |
| `di/RouteModule.kt` | The contract boot guard beside the access guard | 1 |
| `route/api/pois/PoisOnRouteRoutes.kt`, `route/api/settings/SettingsRoutes.kt` | Import the moved DTOs | 1 |
| `model/availability/WatchStatus.kt` (moved) | `@Serializable enum`, `@SerialName` per constant | 2 |
| `model/api/RecgovStatusDto.kt`, `model/api/BookingActionDto.kt` | Three `object` vocabularies become `@Serializable enum class`es; the DTO fields retyped | 2 |
| `model/availability/WatchDoneReason.kt` (new) | `triggered` / `elapsed` | 3 |
| `resources/db/migration/V63__availability_watch_done_reason.sql` (new) | The column + its CHECK; no backfill | 3 |
| `repo/AvailabilityWatchRepo.kt` | `UpdateInput.doneReason`, `Watch.doneReason`, write + read | 3 |
| `repo/AvailabilityPollerRepo.kt` | The reaper stamps `elapsed` | 3 |
| `service/availability/WatchAlertDispatcher.kt` | The trigger stamps `triggered` | 3 |
| `service/availability/AvailabilityWatchApiMapper.kt` | Passes `doneReason` through | 3 |
| `apigen/TsTypes.kt` (new) | The emitted TS shapes: field, interface, enum | 4 |
| `apigen/DescriptorWalk.kt` (new) | One walk of the descriptor graph under one optionality rule | 4 |
| `apigen/ApiTypeGenerator.kt` (new) | Merge, order, render | 4 |
| `apigen/GenerateApiTypes.kt` (new) | `main()`: write or compare | 4 |
| `backend/build.gradle.kts` | `generateApiTypes`, `checkApiTypes`, the kover exclude | 4 |
| `frontend/src/api/generated/api-types.ts` (new, generated) | The committed contract | 4 |
| `frontend/src/api/*.ts` (12 modules) | Mirrors become aliases | 5, 6 |
| `frontend/src/lib/watch-triggers.ts` | The two dead casts go | 5 |
| `frontend/src/features/alerts/alert-rows.ts` | `done_reason`, with the date guess as the pre-migration fallback | 5 |
| `frontend/src/domain/poi/campground-detail.ts` | `typed()` points at the full schema | 6 |
| `Makefile`, `.github/workflows/ci.yml` | `checkApiTypes` in the gate; the generated path in the filter | 7 |
| `AGENTS.md`, `docs/backend-architecture.md`, `docs/frontend-components.md` | The rule, the loop, the rewritten mirror paragraph | 7 |

---
### Task 1: `ApiContract` — every endpoint as data, guarded against the live routing tree

Three private request DTOs move into `model/` first, because a contract row cannot name a
route-private class. Two dead schemas go with them. Then the contract itself, the walker that
reads the mounted tree, the boot guard that compares them, and the layering rule that keeps
`model/` Ktor-free.

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiMethod.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiContract.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/api/SlackTestRequest.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/api/poi/OnRouteRequestDto.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/route/common/RouteInventory.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/poi/PoisOnRouteRequestSchema.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/api/poi/WaypointSchema.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/route/api/pois/PoisOnRouteRoutes.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/route/api/settings/SettingsRoutes.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt:194-199`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/route/common/ApiContractCoverageTest.kt` (new), `backend/src/test/kotlin/ca/floo/roadtrip/LayeringGuardTest.kt`

**Interfaces:**
- Consumes: `io.ktor.server.routing.HttpMethodRouteSelector` (`.method: HttpMethod`), `io.ktor.server.routing.OpenApiRoutePathFormat`, `RoutingNode.path(format)`, `RoutingNode.children`, `RoutingNode.selector`, `io.ktor.server.routing.routingRoot`; every `@Serializable` DTO named in the table below; `ca.floo.roadtrip.route.common.undeclaredAccessRoutes` (the existing sibling guard it sits beside).
- Produces:
  - `ca.floo.roadtrip.model.api.ApiMethod` — `enum class ApiMethod(val wireValue: String) { GET, POST, PUT, DELETE }`
  - `ca.floo.roadtrip.model.api.ApiEndpoint` — `data class ApiEndpoint(val method: ApiMethod, val path: String, val request: KClass<*>? = null, val response: KClass<*>? = null, val errors: List<KClass<*>> = listOf(ApiErrorSchema::class), val conditional: Boolean = false)`
  - `ca.floo.roadtrip.model.api.ApiContract` — `object ApiContract { val endpoints: List<ApiEndpoint>; fun keys(): Set<Pair<String, String>>; fun requiredKeys(): Set<Pair<String, String>> }`
  - `ca.floo.roadtrip.model.api.SlackTestRequest` — `data class SlackTestRequest(val channel: String? = null)`
  - `ca.floo.roadtrip.model.api.poi.OnRouteRequestDto` — `data class OnRouteRequestDto(val waypoints: List<OnRouteWaypointDto> = emptyList(), @SerialName("radius_miles") val radiusMiles: Double? = null, val categories: List<String>? = null)`
  - `ca.floo.roadtrip.model.api.poi.OnRouteWaypointDto` — `data class OnRouteWaypointDto(val lat: Double? = null, val lng: Double? = null)`
  - `ca.floo.roadtrip.route.common.RouteLeaf` — `internal data class RouteLeaf(val method: String, val path: String)`
  - `ca.floo.roadtrip.route.common.methodLeaves` — `internal fun RoutingNode.methodLeaves(): List<RouteLeaf>`
  - `ca.floo.roadtrip.route.common.contractedApiLeaves` — `internal fun RoutingNode.contractedApiLeaves(): Set<RouteLeaf>`
  - `ca.floo.roadtrip.route.common.ContractDrift` — `internal data class ContractDrift(val uncontractedRoutes: List<String>, val unmountedRows: List<String>)`
  - `ca.floo.roadtrip.route.common.apiContractDrift` — `internal fun RoutingNode.apiContractDrift(): ContractDrift`

#### The endpoint list

This is the contract, verbatim. `errors` is omitted below wherever it is the default
`[ApiErrorSchema]`. Paths are exactly what `RoutingNode.path(OpenApiRoutePathFormat)` renders
for the tree `RouteModule.registerKoinRoutes` mounts.

| # | Method | Path | Request | Response (2xx) | Extra errors |
|---|---|---|---|---|---|
| 1 | POST | `/auth/password/begin` | `PasswordBeginRequestDto` | `PasswordBeginResponseDto` | |
| 2 | POST | `/auth/password/complete` | `PasswordCompleteRequestDto` | — (204) | |
| 3 | GET | `/api/me` | | `MeResponseDto` | |
| 4 | GET | `/api/settings` | | `SettingsResponseDto` | |
| 5 | PUT | `/api/settings/profile` | `UpdateProfileRequest` | `SettingsResponseDto` | |
| 6 | PUT | `/api/settings/notifications` | `UpdateNotificationsRequest` | `SettingsResponseDto` | |
| 7 | DELETE | `/api/settings/notifications/slack` | | `SettingsResponseDto` | |
| 8 | POST | `/api/settings/notifications/slack/test` | `SlackTestRequest` | `SlackTestResponseDto` | |
| 9 | POST | `/api/settings/notifications/email/test` | | `EmailTestResponseDto` | |
| 10 | PUT | `/api/settings/recgov` | `UpdateRecgovRequest` | `BookingSettingsDto` | |
| 11 | DELETE | `/api/settings/recgov` | | `RecgovRemovedDto` | |
| 12 | POST | `/api/settings/recgov/login` | | `RecgovLoginResponseDto` | |
| 13 | POST | `/api/settings/recgov/login/mfa` | `RecgovMfaRequest` | `RecgovLoginResponseDto` | |
| 14 | POST | `/api/settings/recgov/verify` | | `RecgovVerifyResponseDto` | |
| 15 | GET | `/api/settings/recgov/status` | | `RecgovStatusDto` | |
| 16 | POST | `/api/booking/add-to-cart` | `AddToCartRequestDto` | `AddToCartResponseDto` | |
| 17 | POST | `/api/pois` | `PoisRequestSchema` | `PoiFeatureCollectionSchema` | |
| 18 | GET | `/api/pois/search` | | `PoiSearchResponseSchema` | |
| 19 | GET | `/api/pois/{id}` | | `PoiDetailFeatureSchema` | |
| 20 | POST | `/api/pois/on-route` | `OnRouteRequestDto` | `PoisOnRouteResponseSchema` | |
| 21 | POST | `/api/pois/availability/bulk` | `BulkAvailabilityRequestDto` | `BulkAvailabilityResponseDto` | `AvailabilityErrorDto` |
| 22 | GET | `/api/pois/{id}/campsites` | | `PoiCampsitesResponseSchema` | |
| 23 | GET | `/api/pois/{id}/campsites/availability` | | `PoiCampsitesAvailabilityResponseDto` | `AvailabilityErrorDto` |
| 24 | GET | `/api/watches` | | `AvailabilityWatchListResponse` | |
| 25 | POST | `/api/watches` | `AvailabilityWatchCreateRequest` | `AvailabilityWatchResponse` | |
| 26 | GET | `/api/watches/{id}` | | `AvailabilityWatchResponse` | |
| 27 | POST | `/api/watches/{id}/modify` | `AvailabilityWatchUpdateRequest` | `AvailabilityWatchResponse` | |
| 28 | POST | `/api/watches/{id}/delete` | | — (204) | |
| 29 | GET | `/api/availability/pollers` | | `AvailabilityPollersListResponse` | |
| 30 | GET | `/api/availability/pollers/summary` | | `AvailabilityPollersSummary` | |
| 31 | GET | `/api/availability/pollers/{id}/runs` | | `AvailabilityRunsListResponse` | |
| 32 | POST | `/api/availability/pollers/{id}/force` | | `CheckNowResponseDto` | `CheckNowCooldownDto` |
| 33 | GET | `/api/availability/runs` | | `AvailabilityRunsListResponse` | |
| 34 | GET | `/api/availability/changes` | | `ListAvailabilityChangesResponse` | |
| 35 | GET | `/api/availability/changes/summary` | | `AvailabilitySnapshotsSummaryResponse` | |
| 36 | GET | `/api/route` | | `RouteFeatureCollectionDto` | `RouteErrorDto` |
| 37 | GET | `/api/geocode` | | `GeocodeResponseDto` | |
| 38 | GET | `/api/build-info` | | `BuildInfoDto` | |
| 39 | GET | `/api/health` | | `HealthResponseDto` | |
| 40 | GET | `/api/health/ready` | | `ReadinessResponseDto` | |
| 41 | POST | `/api/admin/data/import` | | `FanOutResponseSchema` | |
| 42 | POST | `/api/admin/data/import/{target}` | | `RunOutcomeSchema` | `ErrorUnknownTargetSchema`, `ErrorTargetBusySchema` |
| 43 | GET | `/api/admin/data/runs` | | `RunsListSchema` | |
| 44 | GET | `/api/admin/data/runs/{id}` | | `RunDetailSchema` | `ErrorNotFoundSchema` |
| 45 | GET | `/api/admin/data/status` | | `StatusResponseSchema` | |
| 46 | POST | `/api/slack/interactivity` | | — (empty text) | — (`conditional = true`) |

Forty-six rows. `/auth/login`, `/auth/callback` and `/auth/logout` are redirect-only and sit
outside both covered prefixes, so they carry no row (Global Constraints). `/api/docs` and
`/api/docs/openapi.json` are exempt (Resolution 5). Row 46 is `conditional` (Resolution 4).

- [ ] **Step 1: Move `SlackTestRequest` into `model/api/`**

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/api/SlackTestRequest.kt`:

```kotlin
package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

/** Body of `POST /api/settings/notifications/slack/test`. A null channel means the stored one. */
@Serializable
data class SlackTestRequest(
    val channel: String? = null,
)
```

In `backend/src/main/kotlin/ca/floo/roadtrip/route/api/settings/SettingsRoutes.kt`, delete the
local declaration:

```kotlin
/** Body for `POST /api/settings/notifications/slack/test`. */
@Serializable
private data class SlackTestRequest(
    val channel: String? = null,
)
```

and add `import ca.floo.roadtrip.model.api.SlackTestRequest` to the import block. Drop the now
unused `import kotlinx.serialization.Serializable` if nothing else in the file uses it.

- [ ] **Step 2: Move the on-route request body into `model/api/poi/`**

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/api/poi/OnRouteRequestDto.kt`:

```kotlin
package ca.floo.roadtrip.model.api.poi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of `POST /api/pois/on-route`.
 *
 * Every field decodes leniently so the route answers a missing or mistyped one
 * with a message naming it, rather than with kotlinx's own exception text. The
 * real bounds live in the route's `validated(...)`, which needs `RouteConfig`.
 */
@Serializable
data class OnRouteRequestDto(
    val waypoints: List<OnRouteWaypointDto> = emptyList(),
    @SerialName("radius_miles") val radiusMiles: Double? = null,
    val categories: List<String>? = null,
)

@Serializable
data class OnRouteWaypointDto(
    val lat: Double? = null,
    val lng: Double? = null,
)
```

Delete `backend/src/main/kotlin/ca/floo/roadtrip/model/api/poi/PoisOnRouteRequestSchema.kt` and
`backend/src/main/kotlin/ca/floo/roadtrip/model/api/poi/WaypointSchema.kt`.

In `backend/src/main/kotlin/ca/floo/roadtrip/route/api/pois/PoisOnRouteRoutes.kt`, delete both
local `@Serializable` classes and re-home their `validated` members as private extensions.
Replace this block:

```kotlin
@Serializable
private class OnRouteRequestDto(
    val waypoints: List<WaypointDto> = emptyList(),
    @SerialName("radius_miles") val radiusMiles: Double? = null,
    val categories: List<String>? = null,
) {
    fun validated(routeConfig: RouteConfig): OnRouteRequest {
```

… through the end of `private class WaypointDto { … }` with:

```kotlin
private fun OnRouteRequestDto.validated(routeConfig: RouteConfig): OnRouteRequest {
    require(waypoints.size in 2..routeConfig.maxWaypoints) {
        "waypoints must have 2..${routeConfig.maxWaypoints} entries (got ${waypoints.size})"
    }
    val radius = radiusMiles ?: error("radius_miles is missing or not a number")
    require(radius in routeConfig.minCorridorRadiusMiles..routeConfig.maxCorridorRadiusMiles) {
        "radius_miles must be in [${routeConfig.minCorridorRadiusMiles}, ${routeConfig.maxCorridorRadiusMiles}] (got $radius)"
    }
    val parsedCategories =
        categories
            ?.mapNotNull {
                it.trim().takeIf { category -> category.isNotEmpty() }
            }?.let(::canonicalPoiCategories)
            ?.takeIf { it.isNotEmpty() }
    return OnRouteRequest(
        waypoints = waypoints.mapIndexed { index, waypoint -> waypoint.validated(index) },
        radiusMiles = radius,
        categories = parsedCategories,
    )
}

private fun OnRouteWaypointDto.validated(index: Int): OnRouteWaypoint {
    val parsedLat = lat ?: error("waypoint[$index].lat is missing or not a number")
    val parsedLng = lng ?: error("waypoint[$index].lng is missing or not a number")
    require(parsedLat in -90.0..90.0) { "waypoint[$index].lat out of range" }
    require(parsedLng in -180.0..180.0) { "waypoint[$index].lng out of range" }
    return OnRouteWaypoint(lat = parsedLat, lng = parsedLng)
}
```

Add `import ca.floo.roadtrip.model.api.poi.OnRouteRequestDto` and
`import ca.floo.roadtrip.model.api.poi.OnRouteWaypointDto`; drop
`import kotlinx.serialization.SerialName` and `import kotlinx.serialization.Serializable` if
nothing else in the file uses them. `parseOnRouteRequest` is unchanged — it already reads
`dto.validated(routeConfig)`.

- [ ] **Step 3: Run the backend tests to prove the move changed nothing**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.PoisOnRouteRoutesTest' --tests 'ca.floo.roadtrip.route.api.settings.SettingsRoutesTest' --offline -q`
Expected: PASS. Both suites drive the moved bodies over HTTP; identical field types and
defaults mean identical status codes and identical `detail` strings.

- [ ] **Step 4: Write the failing coverage test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/route/common/ApiContractCoverageTest.kt`:

```kotlin
package ca.floo.roadtrip.route.common

import ca.floo.roadtrip.client.mapbox.MapboxGeocoder
import ca.floo.roadtrip.model.api.ApiContract
import ca.floo.roadtrip.model.api.poi.PoiDetailFeatureSchema
import ca.floo.roadtrip.model.api.poi.PoiFeatureCollectionSchema
import ca.floo.roadtrip.model.api.poi.PoiSearchResponseSchema
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.model.domain.poi.Bbox
import ca.floo.roadtrip.model.domain.poi.PoiRow
import ca.floo.roadtrip.config.BuildInfoConfig
import ca.floo.roadtrip.route.api.buildInfoRoutes
import ca.floo.roadtrip.route.api.docs.apiDocsRoutes
import ca.floo.roadtrip.route.api.geocode.geocodeRoutes
import ca.floo.roadtrip.route.api.health.healthRoutes
import ca.floo.roadtrip.route.api.pois.poiRoutes
import ca.floo.roadtrip.route.auth.authRoutes
import ca.floo.roadtrip.service.geocode.GeocodeService
import ca.floo.roadtrip.service.health.ReadinessService
import ca.floo.roadtrip.service.poi.PoiReader
import io.ktor.client.request.get
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.routing.routingRoot
import io.ktor.server.testing.testApplication
import kotlinx.serialization.serializer
import kotlin.reflect.full.createType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract's *authoritative* check is the boot guard in `registerKoinRoutes`,
 * which compares `ApiContract` against the live tree — including the Slack route,
 * mounted only when a signing secret is configured. This test does what
 * `RouteAccessCoverageTest` does for RFC 0010: it exercises the comparator, against
 * a real mounted slice and against synthetic trees shaped like each failure.
 */
class ApiContractCoverageTest {
    @Test
    fun `a real mounted slice is fully contracted`() {
        val drift =
            driftFor {
                apiDocsRoutes()
                authRoutes(wiring = null)
                healthRoutes { ReadinessService.Report(databaseReachable = true) }
                geocodeRoutes(GeocodeService(MapboxGeocoder(token = null)))
                buildInfoRoutes(BuildInfoConfig(env = "test", sha = "0", branch = "test"))
                poiRoutes(EmptyPoiReader)
            }
        assertEquals(emptyList(), drift.uncontractedRoutes)
    }

    @Test
    fun `a mounted route with no contract row is reported`() {
        val drift =
            driftFor {
                get("/api/not-in-the-contract") { call.respondText("ok") }.access(RouteAccess.Anonymous)
            }
        assertEquals(listOf("GET /api/not-in-the-contract"), drift.uncontractedRoutes)
    }

    @Test
    fun `every non-conditional contract row is unmounted in an empty tree`() {
        val drift = driftFor { }
        assertEquals(ApiContract.requiredKeys().size, drift.unmountedRows.size)
        assertTrue(
            drift.unmountedRows.none { it == "POST /api/slack/interactivity" },
            "the conditional Slack row must not be reported as unmounted",
        )
    }

    @Test
    fun `paths outside the covered prefixes are not contracted`() {
        val drift =
            driftFor {
                get("/auth/login") { call.respondText("ok") }.access(RouteAccess.Anonymous)
                get("/data/x") { call.respondText("ok") }.access(RouteAccess.Anonymous)
            }
        assertEquals(emptyList(), drift.uncontractedRoutes)
    }

    @Test
    fun `the swagger subtree is not contracted`() {
        val leaves = leavesFor { apiDocsRoutes() }
        assertEquals(emptySet(), leaves)
    }

    @Test
    fun `no contract row is declared twice`() {
        val keys = ApiContract.endpoints.map { it.method.wireValue to it.path }
        assertEquals(keys.size, keys.toSet().size, "duplicate rows: ${keys.groupBy { it }.filterValues { it.size > 1 }.keys}")
    }

    @Test
    fun `every contract path is under a covered prefix`() {
        assertEquals(
            emptyList(),
            ApiContract.endpoints
                .map { it.path }
                .filterNot { it.startsWith("/api/") || it.startsWith("/auth/password/") }
                .sorted(),
        )
    }

    @Test
    fun `every class the contract names is serializable`() {
        val classes =
            ApiContract.endpoints.flatMap { listOfNotNull(it.request, it.response) + it.errors }.distinct()
        classes.forEach { serializer(it.createType()) }
        assertTrue(classes.size > 40, "expected the contract to name more than 40 distinct DTOs, got ${classes.size}")
    }

    private fun leavesFor(mount: Route.() -> Unit): Set<RouteLeaf> {
        lateinit var leaves: Set<RouteLeaf>
        withMountedTree(mount) { leaves = it.contractedApiLeaves() }
        return leaves
    }

    private fun driftFor(mount: Route.() -> Unit): ContractDrift {
        lateinit var drift: ContractDrift
        withMountedTree(mount) { drift = it.apiContractDrift() }
        return drift
    }

    private fun withMountedTree(
        mount: Route.() -> Unit,
        read: (RoutingNode) -> Unit,
    ) {
        testApplication {
            application {
                routing { mount() }
                read(routingRoot)
            }
            client.get("/__contract_probe__")
        }
    }

    /** The contract cares about the routing tree's shape, never about what a handler answers. */
    private object EmptyPoiReader : PoiReader {
        override fun pois(
            bbox: Bbox,
            zoom: Int?,
            categories: List<String>?,
        ): PoiFeatureCollectionSchema = PoiFeatureCollectionSchema(truncated = false, features = emptyList())

        override fun poisWithinPolygon(
            polygonGeoJson: String,
            categories: List<String>?,
        ): List<PoiRow> = emptyList()

        override fun poiDetail(id: Long): PoiDetailFeatureSchema? = null

        override fun search(
            query: String,
            categories: List<String>,
            limit: Int,
        ): PoiSearchResponseSchema = PoiSearchResponseSchema(results = emptyList())
    }
}
```

- [ ] **Step 5: Run it to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.common.ApiContractCoverageTest' --offline -q`
Expected: FAIL to compile — `Unresolved reference: ApiContract`, `RouteLeaf`, `ContractDrift`,
`contractedApiLeaves`, `apiContractDrift`.

- [ ] **Step 6: Add `ApiMethod`**

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiMethod.kt`:

```kotlin
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
```

- [ ] **Step 7: Add `ApiContract`**

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiContract.kt`:

```kotlin
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
 * Every endpoint the backend serves under `/api/**` and `/auth/password/**`.
 *
 * The single source of truth for the generated TypeScript
 * (`frontend/src/api/generated/api-types.ts`) and, via the boot guard in
 * `registerKoinRoutes`, for the routing tree itself: a route with no row here
 * fails the boot, and a row with no route does too.
 *
 * `/api/docs` and `/api/docs/openapi.json` are exempt — the Swagger subtree is
 * framework-generated and the spec document is a Ktor type, not one of ours.
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
```

- [ ] **Step 8: Add the routing-tree walker and the comparator**

Create `backend/src/main/kotlin/ca/floo/roadtrip/route/common/RouteInventory.kt`:

```kotlin
package ca.floo.roadtrip.route.common

import ca.floo.roadtrip.model.api.ApiContract
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.OpenApiRoutePathFormat
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.path

private const val API_PREFIX = "/api/"
private const val PASSWORD_AUTH_PREFIX = "/auth/password/"

// The Swagger UI subtree is framework-generated, and openapi.json answers with
// io.ktor.openapi.OpenApiDoc — a Ktor type no DTO of ours describes. Both are
// outside the contract, for the same reason the access guard exempts the subtree.
private const val API_DOCS_PATH = "/api/docs"

/** One mounted handler: the verb it answers and the path it answers on. */
internal data class RouteLeaf(
    val method: String,
    val path: String,
)

/** What the mounted tree and [ApiContract] disagree about, as readable lines. */
internal data class ContractDrift(
    val uncontractedRoutes: List<String>,
    val unmountedRows: List<String>,
)

/** Every method leaf under this node, in tree order. */
internal fun RoutingNode.methodLeaves(): List<RouteLeaf> {
    val leaves = mutableListOf<RouteLeaf>()
    collectMethodLeaves(leaves)
    return leaves
}

/** The leaves [ApiContract] is answerable for. */
internal fun RoutingNode.contractedApiLeaves(): Set<RouteLeaf> =
    methodLeaves()
        .filter { it.path.startsWith(API_PREFIX) || it.path.startsWith(PASSWORD_AUTH_PREFIX) }
        .filterNot { it.path == API_DOCS_PATH || it.path.startsWith("$API_DOCS_PATH/") }
        .toSet()

/**
 * Both directions at once: routes nobody declared, and declared rows nobody
 * mounted. A [ApiEndpoint.conditional] row is exempt from the second half only.
 */
internal fun RoutingNode.apiContractDrift(): ContractDrift {
    val mounted = contractedApiLeaves().mapTo(LinkedHashSet()) { it.method to it.path }
    return ContractDrift(
        uncontractedRoutes = (mounted - ApiContract.keys()).map { "${it.first} ${it.second}" }.sorted(),
        unmountedRows = (ApiContract.requiredKeys() - mounted).map { "${it.first} ${it.second}" }.sorted(),
    )
}

private fun RoutingNode.collectMethodLeaves(into: MutableList<RouteLeaf>) {
    (selector as? HttpMethodRouteSelector)?.let { into += RouteLeaf(it.method.value, path(OpenApiRoutePathFormat)) }
    children.forEach { it.collectMethodLeaves(into) }
}
```

- [ ] **Step 9: Run the coverage test to verify it passes**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.common.ApiContractCoverageTest' --offline -q`
Expected: PASS, all eight cases.

- [ ] **Step 10: Add the boot guard**

In `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`, immediately after the existing
access guard (the `check(undeclared.isEmpty())` block at the end of `registerKoinRoutes`), add:

```kotlin
    // The same completeness guarantee for the wire contract: the live tree and
    // model/api/ApiContract.kt must name the same endpoints. A route with no row
    // would ship types nobody generated; a row with no route would generate types
    // nothing serves. ApiContractCoverageTest exercises the comparator.
    val drift = routingRoot.apiContractDrift()
    check(drift.uncontractedRoutes.isEmpty() && drift.unmountedRows.isEmpty()) {
        "ApiContract and the routing tree disagree. " +
            "Routes with no contract row: ${drift.uncontractedRoutes.ifEmpty { listOf("none") }.joinToString()}. " +
            "Contract rows with no route: ${drift.unmountedRows.ifEmpty { listOf("none") }.joinToString()}. " +
            "Fix model/api/ApiContract.kt, then run `make api-types`."
    }
```

Add `import ca.floo.roadtrip.route.common.apiContractDrift` to the import block.

- [ ] **Step 11: Add the `model/` Ktor guard**

In `backend/src/test/kotlin/ca/floo/roadtrip/LayeringGuardTest.kt`, add the constant beside
`SERVICE_PREFIX`:

```kotlin
private const val MODEL_PREFIX = "ca/floo/roadtrip/model/"
```

and the fourth case, after `only the OIDC redirect builder names Ktor under service`:

```kotlin
    @Test
    fun `models never name Ktor`() =
        assertEquals(
            emptyList(),
            sources.filter { (path, text) -> path.startsWith(MODEL_PREFIX) && text.contains(KTOR_PACKAGE) }.map { it.first },
            "models are pure data shapes. ApiContract declares ApiMethod rather than importing io.ktor.http.HttpMethod.",
        )
```

Update the class KDoc's first line from "Three seams that no compiler enforces" to "Four seams
that no compiler enforces", and extend its list with "a contract list that reached for Ktor's
own `HttpMethod`".

- [ ] **Step 12: Verify the guard fires against the live tree**

Temporarily comment out the `/api/build-info` row in `ApiContract.endpoints`, then:

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.OpenApiSmokeTest' --offline -q && ./gradlew :backend:run --offline 2>&1 | head -40`
Expected: the run fails during startup with
`ApiContract and the routing tree disagree. Routes with no contract row: GET /api/build-info.`
Restore the row and re-run; expected: the app boots.

(If a local Postgres is not up, `./gradlew :backend:run` will fail on the database instead. In
that case run `make run` and read the backend log for the same line.)

- [ ] **Step 13: Run the full backend gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 14: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiMethod.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiContract.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/SlackTestRequest.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/poi/OnRouteRequestDto.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/poi/PoisOnRouteRequestSchema.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/poi/WaypointSchema.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/route/common/RouteInventory.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/route/api/pois/PoisOnRouteRoutes.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/route/api/settings/SettingsRoutes.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/common/ApiContractCoverageTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/LayeringGuardTest.kt
git commit -F - <<'MSG'
feat(api): the endpoint contract is data, and the boot guard holds it to the routing tree

ApiContract lists all forty-six endpoints under /api/** and /auth/password/**
with their request, response and error DTOs. registerKoinRoutes compares it
against the live tree in both directions beside the RFC 0010 access guard, so a
route with no row — or a row with no route — fails the boot.

Three request bodies move out of route files into model/api/ so the contract can
name them; the two dead on-route schemas that described a stricter shape than the
route accepts are deleted. LayeringGuardTest gains a fourth seam: model/ never
names Ktor, which is why ApiMethod exists.

Refs #743

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---
### Task 2: The four string vocabularies become `@Serializable` enums

Nothing about the wire changes: every `@SerialName` is today's string, and the repos keep
writing `wireValue`. What changes is that the vocabulary is a type, so the generator can emit
the TS union the frontend hand-maintains today.

**Files:**
- Move: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/WatchStatus.kt` → `backend/src/main/kotlin/ca/floo/roadtrip/model/availability/WatchStatus.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/RecgovStatusDto.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/api/BookingActionDto.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/api/AvailabilityWatchSchema.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/api/AvailabilityWatchUpdateRequest.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchApiMapper.kt`, and every file that referenced `WatchStatus` (mechanical import move, Step 2)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/route/api/settings/RecgovSettingsRoutesTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/route/AvailabilityWatchRoutesTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/route/api/BookingRoutesTest.kt`

**Interfaces:**
- Consumes: `kotlinx.serialization.Serializable`, `kotlinx.serialization.SerialName`; the existing `AvailabilityStatus` precedent at `model/availability/AvailabilityStatus.kt`.
- Produces:
  - `ca.floo.roadtrip.model.availability.WatchStatus` — `@Serializable enum class WatchStatus(val wireValue: String) { ACTIVE, PAUSED, DONE }` with `companion object { fun parse(value: String?): WatchStatus? }` (same signature as today)
  - `ca.floo.roadtrip.model.api.RecgovSessionState` — `@Serializable enum class RecgovSessionState(val wireValue: String) { NOT_CONFIGURED, ACTIVE, NOT_LOGGED_IN, EXPIRED, CHECK_FAILED, COMPANION_UNAVAILABLE }`
  - `ca.floo.roadtrip.model.api.RecgovLoginStatus` — `@Serializable enum class RecgovLoginStatus(val wireValue: String) { OK, MFA_REQUIRED, FAILED }`
  - `ca.floo.roadtrip.model.api.BookingActionStatus` — `@Serializable enum class BookingActionStatus(val wireValue: String) { COMPLETED }`
  - `RecgovStatusDto.session: RecgovSessionState` (was `String`)
  - `RecgovLoginResponseDto.status: RecgovLoginStatus` (was `String`)
  - `AddToCartResponseDto.status: BookingActionStatus` (was `String`)
  - `AvailabilityWatchSchema.status: WatchStatus` (was `String`)
  - `AvailabilityWatchUpdateRequest.status: WatchStatus?` (was `String?`)

- [ ] **Step 1: Write the failing wire-pin tests**

These pin that the JSON strings are unchanged, so the refactor cannot quietly rename a value.

Add to `backend/src/test/kotlin/ca/floo/roadtrip/route/api/settings/RecgovSettingsRoutesTest.kt`,
inside the existing class:

```kotlin
    @Test
    fun `the session vocabulary keeps its wire strings`() {
        assertEquals(
            listOf("not_configured", "active", "not_logged_in", "expired", "check_failed", "companion_unavailable"),
            RecgovSessionState.entries.map { it.wireValue },
        )
        assertEquals(
            listOf("ok", "mfa_required", "failed"),
            RecgovLoginStatus.entries.map { it.wireValue },
        )
    }
```

Add to `backend/src/test/kotlin/ca/floo/roadtrip/route/AvailabilityWatchRoutesTest.kt`, inside
the existing class:

```kotlin
    @Test
    fun `the watch status vocabulary keeps its wire strings`() {
        assertEquals(listOf("active", "paused", "done"), WatchStatus.entries.map { it.wireValue })
        assertEquals(WatchStatus.PAUSED, WatchStatus.parse("paused"))
        assertEquals(null, WatchStatus.parse("retired"))
    }
```

with `import ca.floo.roadtrip.model.availability.WatchStatus` and
`import kotlin.test.assertEquals` if the file does not already have them.

Add to `backend/src/test/kotlin/ca/floo/roadtrip/route/api/BookingRoutesTest.kt`, inside the
existing class:

```kotlin
    @Test
    fun `the booking action vocabulary keeps its wire string`() {
        assertEquals(listOf("completed"), BookingActionStatus.entries.map { it.wireValue })
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.api.settings.RecgovSettingsRoutesTest' --tests 'ca.floo.roadtrip.route.AvailabilityWatchRoutesTest' --tests 'ca.floo.roadtrip.route.api.BookingRoutesTest' --offline -q`
Expected: FAIL to compile — `Unresolved reference: entries` on the three `object`s, and
`Unresolved reference: ca.floo.roadtrip.model.availability.WatchStatus`.

- [ ] **Step 3: Move `WatchStatus` into `model/availability/`**

```bash
git mv backend/src/main/kotlin/ca/floo/roadtrip/service/availability/WatchStatus.kt \
       backend/src/main/kotlin/ca/floo/roadtrip/model/availability/WatchStatus.kt
```

Replace the file's contents with:

```kotlin
package ca.floo.roadtrip.model.availability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A watch's lifecycle state. [wireValue] is both the JSON value and the
 * `availability_watch.status` column value; the `@SerialName`s repeat it so the
 * generated TypeScript union is the same vocabulary.
 */
@Serializable
enum class WatchStatus(
    val wireValue: String,
) {
    @SerialName("active")
    ACTIVE("active"),

    @SerialName("paused")
    PAUSED("paused"),

    @SerialName("done")
    DONE("done"),
    ;

    companion object {
        fun parse(value: String?): WatchStatus? = entries.firstOrNull { it.wireValue == value }
    }
}
```

- [ ] **Step 4: Repoint every reference**

Rewrite the fourteen explicit imports:

```bash
grep -rl 'ca\.floo\.roadtrip\.service\.availability\.WatchStatus' backend/src \
  | xargs sed -i '' 's#ca\.floo\.roadtrip\.service\.availability\.WatchStatus#ca.floo.roadtrip.model.availability.WatchStatus#g'
```

(On GNU sed drop the `''` after `-i`.)

Then add `import ca.floo.roadtrip.model.availability.WatchStatus` to the ten files that used to
reach it same-package — seven in main:

- `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollerMembership.kt`
- `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchController.kt`
- `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchRequestMapper.kt`
- `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchService.kt`
- `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/WatchAlertDispatcher.kt`
- `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/WatchLifecycleNotifications.kt`
- `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/WatchTriggerCapabilityValidator.kt`

and three in test:

- `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchServiceTest.kt`
- `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/TriggerActionHandlerTest.kt`
- `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/WatchNotificationTargetResolverTest.kt`

Then let the compiler enumerate anything missed:

Run: `./gradlew :backend:compileTestKotlin --offline -q`
Expected: PASS. Every remaining `Unresolved reference: WatchStatus` is a file that needs the
same import line; add it and re-run until clean.

Then sort the imports:

Run: `./gradlew :backend:ktlintFormat --offline -q`

- [ ] **Step 5: Turn the three `object` vocabularies into enums**

In `backend/src/main/kotlin/ca/floo/roadtrip/model/api/RecgovStatusDto.kt`, replace both
`object` declarations:

```kotlin
/** Wire vocabulary for [RecgovStatusDto.session]. */
@Serializable
enum class RecgovSessionState(
    val wireValue: String,
) {
    @SerialName("not_configured")
    NOT_CONFIGURED("not_configured"),

    @SerialName("active")
    ACTIVE("active"),

    /**
     * Credentials are saved but this profile has never been signed in — the
     * companion has no session to have lost. Distinct from [EXPIRED] because
     * the user has not failed at anything yet; they simply have not started.
     */
    @SerialName("not_logged_in")
    NOT_LOGGED_IN("not_logged_in"),

    @SerialName("expired")
    EXPIRED("expired"),

    /** The companion answered, but its own health check threw. Not the user's problem. */
    @SerialName("check_failed")
    CHECK_FAILED("check_failed"),

    /** The companion could not be asked. Never an error — the row just says so. */
    @SerialName("companion_unavailable")
    COMPANION_UNAVAILABLE("companion_unavailable"),
}

/** Wire vocabulary for [RecgovLoginResponseDto.status]. */
@Serializable
enum class RecgovLoginStatus(
    val wireValue: String,
) {
    @SerialName("ok")
    OK("ok"),

    @SerialName("mfa_required")
    MFA_REQUIRED("mfa_required"),

    @SerialName("failed")
    FAILED("failed"),
}
```

In the same file, retype the two fields and delete the now-redundant field comments:

```kotlin
    val session: RecgovSessionState,
```

(replacing ``/** One of [RecgovSessionState]. */`` + ``val session: String,``) and

```kotlin
    val status: RecgovLoginStatus,
```

(replacing ``/** One of [RecgovLoginStatus]. */`` + ``val status: String,``).

In `backend/src/main/kotlin/ca/floo/roadtrip/model/api/BookingActionDto.kt`:

```kotlin
/** Wire vocabulary for [AddToCartResponseDto.status]. */
@Serializable
enum class BookingActionStatus(
    val wireValue: String,
) {
    @SerialName("completed")
    COMPLETED("completed"),
}
```

and retype the field, keeping its comment:

```kotlin
    /** [BookingActionStatus.COMPLETED]; a failure is an HTTP error, not a status. */
    val status: BookingActionStatus,
```

- [ ] **Step 6: Retype the two watch DTO fields**

In `backend/src/main/kotlin/ca/floo/roadtrip/model/api/AvailabilityWatchSchema.kt`, change
`val status: String,` to `val status: WatchStatus,` and add
`import ca.floo.roadtrip.model.availability.WatchStatus`.

In `backend/src/main/kotlin/ca/floo/roadtrip/model/api/AvailabilityWatchUpdateRequest.kt`,
change `val status: String? = null,` to `val status: WatchStatus? = null,` and add the same
import.

In `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchApiMapper.kt`,
change `status = watch.status.wireValue,` to `status = watch.status,`.

- [ ] **Step 7: Fix the call sites the compiler names**

Run: `./gradlew :backend:compileTestKotlin --offline -q`
Expected: two failures, both in
`backend/src/test/kotlin/ca/floo/roadtrip/route/api/settings/RecgovSettingsRoutesTest.kt`, where
an enum is compared with a JSON string. Change:

```kotlin
        assertEquals(RecgovSessionState.COMPANION_UNAVAILABLE, json["session"]!!.jsonPrimitive.content)
```

to

```kotlin
        assertEquals(RecgovSessionState.COMPANION_UNAVAILABLE.wireValue, json["session"]!!.jsonPrimitive.content)
```

and the same for

```kotlin
        assertEquals(RecgovLoginStatus.FAILED, json["status"]!!.jsonPrimitive.content)
        assertEquals(RecgovLoginStatus.MFA_REQUIRED, json["status"]!!.jsonPrimitive.content)
```

which become `RecgovLoginStatus.FAILED.wireValue` and `RecgovLoginStatus.MFA_REQUIRED.wireValue`.

In `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchRequestMapper.kt:56-64`,
the parse is now dead — the field arrives typed. Replace:

```kotlin
        val status =
            req.status?.let {
                WatchStatus.parse(it)
                    ?: return WatchRequestMapping.Invalid(
                        error = "invalid_status",
                        detail = "status must be active, paused, or done",
                    )
            }
        return valid(ParsedUpdateWatchRequest(targets = null, dateWindow = null, status = status))
```

with:

```kotlin
        return valid(ParsedUpdateWatchRequest(targets = null, dateWindow = null, status = req.status))
```

This is the trade Resolution 16 records: a bad `status` in the modify body is now refused by
the decoder as `400 invalid_body` rather than by the mapper as `400 invalid_status`. The
`invalid_status` code in `AvailabilityWatchRoutes.kt:50` is a different thing — the `?status=`
**query** parameter on `GET /api/watches`, still a `String` parse — and does not change.

- [ ] **Step 8: Run the wire-pin tests plus every suite that touches these DTOs**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.api.settings.RecgovSettingsRoutesTest' --tests 'ca.floo.roadtrip.route.AvailabilityWatchRoutesTest' --tests 'ca.floo.roadtrip.route.api.BookingRoutesTest' --tests 'ca.floo.roadtrip.service.settings.RecGovCredentialServiceTest' --tests 'ca.floo.roadtrip.repo.AvailabilityWatchRepoTest' --offline -q`
Expected: PASS.

- [ ] **Step 9: Prove the wire is byte-identical for a watch**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.AvailabilityWatchRoutesTest' --offline -q`
Expected: PASS — the suite already asserts `status` on the JSON of `GET /api/watches` and
`POST /api/watches`, so an unchanged pass is the proof that `"active"` still goes out as
`"active"`.

- [ ] **Step 10: Run the full backend gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/model/availability/WatchStatus.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/availability/WatchStatus.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/RecgovStatusDto.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/BookingActionDto.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/AvailabilityWatchSchema.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/AvailabilityWatchUpdateRequest.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service \
        backend/src/main/kotlin/ca/floo/roadtrip/repo \
        backend/src/main/kotlin/ca/floo/roadtrip/di \
        backend/src/main/kotlin/ca/floo/roadtrip/route \
        backend/src/test/kotlin/ca/floo/roadtrip
git commit -F - <<'MSG'
refactor(api): the four wire vocabularies are enums, not strings beside a const object

WatchStatus, RecgovSessionState, RecgovLoginStatus and BookingActionStatus carry
one @SerialName per constant, equal to the string that already went out, and the
DTO fields that carried them are typed. Persistence is untouched: the repos still
read and write wireValue.

WatchStatus moves to model/availability/ beside AvailabilityStatus, because a
model/api DTO cannot import service/ without inverting the layer order.

Refs #743

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

### Task 3: `V63` — why a watch ended is recorded, not guessed

Two transitions set `status = done`: `WatchAlertDispatcher` after a delivered alert on a watch
with `stop_when_triggered`, and `AvailabilityPollerRepo.reapElapsedWatches` when the window
passed. Neither persists which one happened, so `alert-rows.ts` infers it from `end_date` and
mislabels a watch triggered on its last day. This records it.

**Files:**
- Create: `backend/src/main/resources/db/migration/V63__availability_watch_done_reason.sql`, `backend/src/main/kotlin/ca/floo/roadtrip/model/availability/WatchDoneReason.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepo.kt:41-51,63-70,319-348,363-380`, `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepo.kt:367-378`, `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/WatchAlertDispatcher.kt:196-199`, `backend/src/main/kotlin/ca/floo/roadtrip/model/api/AvailabilityWatchSchema.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchApiMapper.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepoTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepoTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/WatchAlertDispatcherTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/route/AvailabilityWatchRoutesTest.kt`

**Interfaces:**
- Consumes: `WatchStatus` (Task 2); `AvailabilityWatchRepo.UpdateInput`, `.Watch`, `.update`, `.fromRecord`; the generated `AVAILABILITY_WATCH` table, which gains `DONE_REASON` when `generateJooq` re-runs over `V63`.
- Produces:
  - `ca.floo.roadtrip.model.availability.WatchDoneReason` — `@Serializable enum class WatchDoneReason(val wireValue: String) { TRIGGERED("triggered"), ELAPSED("elapsed") }`
  - `AvailabilityWatchRepo.UpdateInput.doneReason: WatchDoneReason?` (defaults `null`)
  - `AvailabilityWatchRepo.Watch.doneReason: WatchDoneReason?`
  - `AvailabilityWatchSchema.doneReason: WatchDoneReason?` — `@SerialName("done_reason") val doneReason: WatchDoneReason? = null`

- [ ] **Step 1: Write the failing tests**

Add to `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepoTest.kt`:

```kotlin
    @Test
    fun `done reason round-trips and starts null`() {
        val id = seedWatch()
        assertEquals(null, repo.findById(id)!!.doneReason)

        val updated =
            repo.update(
                id,
                AvailabilityWatchRepo.UpdateInput(status = WatchStatus.DONE, doneReason = WatchDoneReason.TRIGGERED),
            )

        assertEquals(WatchStatus.DONE, updated!!.status)
        assertEquals(WatchDoneReason.TRIGGERED, updated.doneReason)
        assertEquals(WatchDoneReason.TRIGGERED, repo.findById(id)!!.doneReason)
    }
```

using whatever the suite's existing seed helper is called (`seedWatch()` here; match the file)
and adding `import ca.floo.roadtrip.model.availability.WatchDoneReason`.

Add to `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepoTest.kt`:

```kotlin
    @Test
    fun `the reaper records why the watch ended`() {
        val watchId = seedElapsedWatch()

        pollerRepo.reapElapsedWatches()

        val reaped = watchRepo.findById(watchId)!!
        assertEquals(WatchStatus.DONE, reaped.status)
        assertEquals(WatchDoneReason.ELAPSED, reaped.doneReason)
    }
```

matching the suite's existing helper for seeding a watch whose `end_date` is in the past, and
the `pollerRepo` / `watchRepo` handles it already holds.

Add to `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/WatchAlertDispatcherTest.kt`,
in the case that already asserts a `stop_when_triggered` watch goes to `done`:

```kotlin
        assertEquals(WatchDoneReason.TRIGGERED, watchRepo.findById(watch.id)!!.doneReason)
```

Add to `backend/src/test/kotlin/ca/floo/roadtrip/route/AvailabilityWatchRoutesTest.kt`:

```kotlin
    @Test
    fun `the watch payload carries done_reason only once one is recorded`() =
        testApplication {
            val id = seedWatch()
            application { routing { testWatchRoutes() } }

            val before = Json.parseToJsonElement(client.getAsUser("/api/watches/$id").bodyAsText()).jsonObject
            assertEquals(null, before["watch"]!!.jsonObject["done_reason"])

            watchRepo.update(
                id,
                AvailabilityWatchRepo.UpdateInput(status = WatchStatus.DONE, doneReason = WatchDoneReason.ELAPSED),
            )

            val after = Json.parseToJsonElement(client.getAsUser("/api/watches/$id").bodyAsText()).jsonObject
            assertEquals("elapsed", after["watch"]!!.jsonObject["done_reason"]!!.jsonPrimitive.content)
        }
```

matching the suite's existing helpers for mounting the routes and issuing an authenticated GET.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.AvailabilityWatchRepoTest' --tests 'ca.floo.roadtrip.repo.AvailabilityPollerRepoTest' --tests 'ca.floo.roadtrip.service.availability.WatchAlertDispatcherTest' --tests 'ca.floo.roadtrip.route.AvailabilityWatchRoutesTest' --offline -q`
Expected: FAIL to compile — `Unresolved reference: WatchDoneReason`, `doneReason`.

- [ ] **Step 3: Add the migration**

Create `backend/src/main/resources/db/migration/V63__availability_watch_done_reason.sql`:

```sql
-- Why a watch reached `done`: availability was found and the watch was told to
-- stop, or its window elapsed. Both transitions used to persist nothing, so the
-- frontend guessed from end_date and mislabelled a watch triggered on its last
-- day. No backfill: a row that went done before this migration honestly does not
-- know, and NULL says so.
ALTER TABLE availability_watch
    ADD COLUMN done_reason text;

ALTER TABLE availability_watch
    ADD CONSTRAINT availability_watch_done_reason_check
        CHECK (done_reason IS NULL OR done_reason IN ('triggered', 'elapsed'));
```

`V62` is the highest applied migration today; never edit one that has run. A nullable
`ADD COLUMN` plus a `CHECK` is metadata-only and runs inside a transaction, so no `.conf`
sibling is needed.

- [ ] **Step 4: Add the enum**

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/availability/WatchDoneReason.kt`:

```kotlin
package ca.floo.roadtrip.model.availability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why a watch reached [WatchStatus.DONE]. Null on any row that went done before
 * `V63` — the transition did not record it, and nothing backfills a guess.
 */
@Serializable
enum class WatchDoneReason(
    val wireValue: String,
) {
    /** An alert was delivered on a watch with `stop_when_triggered`. */
    @SerialName("triggered")
    TRIGGERED("triggered"),

    /** The watch's own window passed; the reaper retired it. */
    @SerialName("elapsed")
    ELAPSED("elapsed"),
    ;

    companion object {
        fun parse(value: String?): WatchDoneReason? = entries.firstOrNull { it.wireValue == value }
    }
}
```

- [ ] **Step 5: Carry it through the repo**

In `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepo.kt`:

Add to `UpdateInput`, after `status`:

```kotlin
        val doneReason: WatchDoneReason? = null,
```

Add to `Watch`, after `status`:

```kotlin
        // Null on any row retired before V63, and on every row that is not done.
        val doneReason: WatchDoneReason?,
```

In `update`, after the `status` line:

```kotlin
        if (input.doneReason != null) query = query.set(AVAILABILITY_WATCH.DONE_REASON, input.doneReason.wireValue)
```

In `fromRecord`, after `status`:

```kotlin
            doneReason = WatchDoneReason.parse(r.get(AVAILABILITY_WATCH.DONE_REASON)),
```

Add `import ca.floo.roadtrip.model.availability.WatchDoneReason`.

In `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepo.kt`, in
`reapElapsedWatches`, add one `set` to the single `UPDATE` that already retires the rows:

```kotlin
                    .set(AVAILABILITY_WATCH.STATUS, WatchStatus.DONE.wireValue)
                    .set(AVAILABILITY_WATCH.DONE_REASON, WatchDoneReason.ELAPSED.wireValue)
                    .set(AVAILABILITY_WATCH.UPDATED_AT, OffsetDateTime.now())
```

Add `import ca.floo.roadtrip.model.availability.WatchDoneReason`.

- [ ] **Step 6: Stamp the trigger**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/WatchAlertDispatcher.kt`,
change the one-line retire:

```kotlin
        if (fired && watch.stopWhenTriggered) {
            watchRepo.update(
                watch.id,
                AvailabilityWatchRepo.UpdateInput(status = WatchStatus.DONE, doneReason = WatchDoneReason.TRIGGERED),
            )
        }
```

Add `import ca.floo.roadtrip.model.availability.WatchDoneReason`.

- [ ] **Step 7: Serve it**

In `backend/src/main/kotlin/ca/floo/roadtrip/model/api/AvailabilityWatchSchema.kt`, add the
field directly after `status`:

```kotlin
    val status: WatchStatus,
    // Null on any row retired before V63, and on every row that is not done.
    @SerialName("done_reason") val doneReason: WatchDoneReason? = null,
```

with `import ca.floo.roadtrip.model.availability.WatchDoneReason`.

In `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchApiMapper.kt`,
in `schema(...)`, after `status = watch.status,`:

```kotlin
            doneReason = watch.doneReason,
```

- [ ] **Step 8: Regenerate the jOOQ classes and run the tests**

`generateJooq` declares the migration directory as an input, and
`generateSchemaSourceOnCompilation` is `true`, so the next compile picks `V63` up on its own.
Docker must be running.

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.AvailabilityWatchRepoTest' --tests 'ca.floo.roadtrip.repo.AvailabilityPollerRepoTest' --tests 'ca.floo.roadtrip.service.availability.WatchAlertDispatcherTest' --tests 'ca.floo.roadtrip.route.AvailabilityWatchRoutesTest' --offline -q`
Expected: PASS.

- [ ] **Step 9: Check the schema-pinning suites**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.CanonicalCatalogSchemaTest' --tests 'ca.floo.roadtrip.repo.JooqCodegenDriftTest' --offline -q`
Expected: PASS. If either enumerates `availability_watch`'s columns, add `done_reason` to that
list; if neither test exists under those names, the command reports no matching tests, which is
also a pass for this step.

- [ ] **Step 10: Run the full backend gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/resources/db/migration/V63__availability_watch_done_reason.sql \
        backend/src/main/kotlin/ca/floo/roadtrip/model/availability/WatchDoneReason.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/AvailabilityWatchSchema.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepo.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepo.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/availability/WatchAlertDispatcher.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchApiMapper.kt \
        backend/src/test/kotlin/ca/floo/roadtrip
git commit -F - <<'MSG'
feat(watches): a done watch records why it ended

V63 adds availability_watch.done_reason ('triggered' | 'elapsed'), written by the
two transitions that set status = done: WatchAlertDispatcher after a delivered
alert on a stop_when_triggered watch, and reapElapsedWatches in the same
statement that retires the row. No backfill — a row retired before V63 does not
know, and NULL says so.

AvailabilityWatchSchema serves done_reason, so the frontend can stop inferring it
from end_date and stop mislabelling a watch triggered on its last day.

Refs #743

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---
### Task 4: The generator, the two Gradle tasks, and the first committed `api-types.ts`

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/apigen/TsTypes.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/apigen/DescriptorWalk.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/apigen/ApiTypeGenerator.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/apigen/GenerateApiTypes.kt`, `frontend/src/api/generated/api-types.ts` (generated)
- Modify: `backend/build.gradle.kts`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/apigen/ApiTypeGeneratorTest.kt` (new)

**Interfaces:**
- Consumes: `ApiContract.endpoints`, `ApiEndpoint`, `ApiMethod` (Task 1); `kotlinx.serialization.serializer(KType)`; `kotlin.reflect.full.createType`; `SerialDescriptor` (`serialName`, `kind`, `elementsCount`, `getElementName`, `getElementDescriptor`, `isElementOptional`, `isNullable`, `nonNullOriginal`); `PrimitiveKind.*`, `StructureKind.CLASS/LIST/MAP/OBJECT`, `SerialKind.ENUM`, `PolymorphicKind`.
- Produces:
  - `ca.floo.roadtrip.apigen.TsField` / `TsInterface` / `TsEnum` / `TsEndpoint` — the emitted shapes
  - `ca.floo.roadtrip.apigen.ApiTypeGenerationException` — thrown for anything unmappable, always naming the class
  - `ca.floo.roadtrip.apigen.Optionality` — `enum class Optionality { RESPONSE, REQUEST }`
  - `ca.floo.roadtrip.apigen.tsName` — `internal fun tsName(serialName: String): String`
  - `ca.floo.roadtrip.apigen.DescriptorWalk` — `internal class DescriptorWalk(optionality: Optionality, declaredBy: MutableMap<String, String>)` with `val interfaces: MutableMap<String, TsInterface>`, `val enums: MutableMap<String, TsEnum>`, `fun typeOf(descriptor: SerialDescriptor): String`
  - `ca.floo.roadtrip.apigen.generateApiTypes` — `internal fun generateApiTypes(endpoints: List<ApiEndpoint> = ApiContract.endpoints): String`
  - `ca.floo.roadtrip.apigen.main` — `fun main(args: Array<String>)`, `args[0]` = target path, optional `--check`
  - Gradle tasks `:backend:generateApiTypes` and `:backend:checkApiTypes`

- [ ] **Step 1: Write the failing generator tests**

Create `backend/src/test/kotlin/ca/floo/roadtrip/apigen/ApiTypeGeneratorTest.kt`:

```kotlin
package ca.floo.roadtrip.apigen

import ca.floo.roadtrip.model.api.ApiEndpoint
import ca.floo.roadtrip.model.api.ApiMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
enum class FixtureFlavour(
    val wireValue: String,
) {
    @SerialName("sweet")
    SWEET("sweet"),

    @SerialName("sour")
    SOUR("sour"),
}

@Serializable
data class FixtureScalars(
    val text: String,
    val letter: Char,
    val count: Int,
    val big: Long,
    val ratio: Double,
    val flag: Boolean,
)

@Serializable
data class FixtureShapes(
    val names: List<String>,
    val tags: Set<String>,
    @SerialName("by_id") val byId: Map<Long, FixtureScalars>,
    val flavour: FixtureFlavour,
    val blob: JsonElement,
    val bag: JsonObject,
    val rows: JsonArray,
)

@Serializable
data class FixtureOptionality(
    val required: String,
    val nullable: String? = null,
    val defaulted: Int = 3,
    @SerialName("nullable_no_default") val nullableNoDefault: String?,
)

@Serializable
data class FixtureNested(
    val inner: FixtureScalars?,
)

@Serializable
sealed interface FixtureSealed {
    @Serializable
    data class One(
        val a: String,
    ) : FixtureSealed
}

/** Same simple name as [FixtureScalars] under a different serial name: a collision. */
@Serializable
@SerialName("ca.floo.roadtrip.apigen.other.FixtureScalars")
data class FixtureCollider(
    val x: String,
)

/**
 * The generator, against a fixture DTO set that exercises every row of the
 * mapping table and every failure it is supposed to refuse.
 */
class ApiTypeGeneratorTest {
    @Test
    fun `the mapping table`() {
        val ts = generateApiTypes(listOf(responseRow(FixtureShapes::class)))
        assertBlock(
            ts,
            """
            export interface FixtureScalars {
              text: string;
              letter: string;
              count: number;
              big: number;
              ratio: number;
              flag: boolean;
            }
            """.trimIndent(),
        )
        assertBlock(
            ts,
            """
            export interface FixtureShapes {
              names: string[];
              tags: string[];
              by_id: Record<string, FixtureScalars>;
              flavour: FixtureFlavour;
              blob: unknown;
              bag: Record<string, unknown>;
              rows: unknown[];
            }
            """.trimIndent(),
        )
        assertTrue(ts.contains("export type FixtureFlavour = 'sweet' | 'sour';"), ts)
    }

    @Test
    fun `response optionality follows nullability alone`() {
        val ts = generateApiTypes(listOf(responseRow(FixtureOptionality::class)))
        assertBlock(
            ts,
            """
            export interface FixtureOptionality {
              required: string;
              nullable?: string;
              defaulted: number;
              nullable_no_default?: string;
            }
            """.trimIndent(),
        )
        assertFalse(ts.contains("| null"), "the encoder never emits null, so no arm may say so")
    }

    @Test
    fun `request optionality also honours defaults`() {
        val ts = generateApiTypes(listOf(requestRow(FixtureOptionality::class)))
        assertBlock(
            ts,
            """
            export interface FixtureOptionality {
              required: string;
              nullable?: string;
              defaulted?: number;
              nullable_no_default?: string;
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a nullable nested class is still walked`() {
        val ts = generateApiTypes(listOf(responseRow(FixtureNested::class)))
        assertBlock(
            ts,
            """
            export interface FixtureNested {
              inner?: FixtureScalars;
            }
            """.trimIndent(),
        )
        assertTrue(ts.contains("export interface FixtureScalars {"), ts)
    }

    @Test
    fun `a class used as both request and response must agree on optionality`() {
        val failure =
            assertFailsWith<ApiTypeGenerationException> {
                generateApiTypes(listOf(responseRow(FixtureOptionality::class), requestRow(FixtureOptionality::class)))
            }
        assertTrue(failure.message!!.contains("FixtureOptionality"), failure.message!!)
        assertTrue(failure.message!!.contains("defaulted"), failure.message!!)
    }

    @Test
    fun `two classes with the same simple name fail generation`() {
        val failure =
            assertFailsWith<ApiTypeGenerationException> {
                generateApiTypes(listOf(responseRow(FixtureScalars::class), responseRow(FixtureCollider::class)))
            }
        assertTrue(failure.message!!.contains("FixtureScalars"), failure.message!!)
    }

    @Test
    fun `a sealed type fails generation by name`() {
        val failure =
            assertFailsWith<ApiTypeGenerationException> { generateApiTypes(listOf(responseRow(FixtureSealed::class))) }
        assertTrue(failure.message!!.contains("FixtureSealed"), failure.message!!)
        assertTrue(failure.message!!.contains("sealed or polymorphic"), failure.message!!)
    }

    @Test
    fun `output is deterministic, sorted by name, LF only, newline terminated`() {
        val rows = listOf(responseRow(FixtureShapes::class), responseRow(FixtureNested::class))
        val first = generateApiTypes(rows)
        assertEquals(first, generateApiTypes(rows))
        val declared =
            Regex("^export (?:interface|type) (\\w+)", RegexOption.MULTILINE)
                .findAll(first)
                .map { it.groupValues[1] }
                .toList()
        assertEquals(declared.sorted(), declared, "declarations must be sorted by name")
        assertTrue(first.endsWith("\n"), "the file must end with a newline")
        assertFalse(first.contains("\r"), "the file must be LF only")
    }

    @Test
    fun `the endpoint literal names the request and response types`() {
        val ts =
            generateApiTypes(
                listOf(
                    ApiEndpoint(
                        ApiMethod.POST,
                        "/api/fixtures",
                        FixtureOptionality::class,
                        FixtureShapes::class,
                        errors = emptyList(),
                    ),
                ),
            )
        assertTrue(
            ts.contains("{ method: 'POST', path: '/api/fixtures', request: 'FixtureOptionality', response: 'FixtureShapes' },"),
            ts,
        )
        assertTrue(ts.trimEnd().endsWith("] as const;"), ts)
    }

    @Test
    fun `the real contract generates the types this phase exists for`() {
        val ts = generateApiTypes()
        assertTrue(ts.contains("export interface CampsiteDto {"), "CampsiteDto missing")
        assertTrue(ts.contains("  booking_provider?: string;"), "the campsite booking_provider field is missing")
        assertTrue(ts.contains("export type WatchStatus = 'active' | 'paused' | 'done';"), "the watch status union is missing")
        assertTrue(ts.contains("export type WatchDoneReason = 'triggered' | 'elapsed';"), "the done reason union is missing")
        assertTrue(ts.contains("  region?: string;"), "the search hit's region is missing")
    }

    private fun responseRow(kClass: KClass<*>): ApiEndpoint =
        ApiEndpoint(ApiMethod.GET, "/api/fixture/${kClass.simpleName}", response = kClass, errors = emptyList())

    private fun requestRow(kClass: KClass<*>): ApiEndpoint =
        ApiEndpoint(ApiMethod.POST, "/api/fixture/${kClass.simpleName}", request = kClass, errors = emptyList())

    private fun assertBlock(
        generated: String,
        block: String,
    ) {
        assertTrue(generated.contains(block), "expected:\n$block\n\nin:\n$generated")
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.apigen.ApiTypeGeneratorTest' --offline -q`
Expected: FAIL to compile — `Unresolved reference: generateApiTypes`, `ApiTypeGenerationException`.

- [ ] **Step 3: Add the emitted shapes**

Create `backend/src/main/kotlin/ca/floo/roadtrip/apigen/TsTypes.kt`:

```kotlin
package ca.floo.roadtrip.apigen

/** One property of a generated interface. */
internal data class TsField(
    val name: String,
    val type: String,
    val optional: Boolean,
)

/** One `export interface`. Fields stay in Kotlin declaration order. */
internal data class TsInterface(
    val name: String,
    val fields: List<TsField>,
)

/** One `export type X = 'a' | 'b'`, from an enum's serial names. */
internal data class TsEnum(
    val name: String,
    val values: List<String>,
)

/** One row of the emitted `API_ENDPOINTS` literal. */
internal data class TsEndpoint(
    val method: String,
    val path: String,
    val request: String?,
    val response: String?,
)

/**
 * Anything the contract cannot express in TypeScript. Every message names the
 * class, because the reader is someone who just added a DTO and needs to know
 * which one the build refused.
 */
internal class ApiTypeGenerationException(
    message: String,
) : IllegalStateException(message)
```

- [ ] **Step 4: Add the descriptor walk**

Create `backend/src/main/kotlin/ca/floo/roadtrip/apigen/DescriptorWalk.kt`:

```kotlin
package ca.floo.roadtrip.apigen

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.nonNullOriginal

private const val TS_STRING = "string"
private const val TS_NUMBER = "number"
private const val TS_BOOLEAN = "boolean"
private const val TS_UNKNOWN = "unknown"
private const val TS_UNKNOWN_RECORD = "Record<string, unknown>"
private const val TS_UNKNOWN_ARRAY = "unknown[]"
private const val TS_JSON_PRIMITIVE = "string | number | boolean"

private const val JSON_PACKAGE = "kotlinx.serialization.json."
private const val JSON_ELEMENT = "kotlinx.serialization.json.JsonElement"
private const val JSON_OBJECT = "kotlinx.serialization.json.JsonObject"
private const val JSON_ARRAY = "kotlinx.serialization.json.JsonArray"
private const val JSON_PRIMITIVE = "kotlinx.serialization.json.JsonPrimitive"

/** A map descriptor's elements are (key, value); JSON object keys are always strings. */
private const val MAP_VALUE_ELEMENT = 1

/** Which side of the wire a walk describes. It decides optionality, nothing else. */
internal enum class Optionality { RESPONSE, REQUEST }

/**
 * The TypeScript name for a serial name: drop the package segments (the
 * lowercase-initial ones) and join what is left, so a nested
 * `…ReadinessResponseDto.State` becomes `ReadinessResponseDtoState` rather than
 * a bare `State` that would collide with the next nested enum.
 */
internal fun tsName(serialName: String): String =
    serialName
        .split('.')
        .dropWhile { it.isEmpty() || it.first().isLowerCase() }
        .joinToString("")
        .ifEmpty { serialName.substringAfterLast('.') }

/**
 * One walk of the descriptor graph under one [Optionality].
 *
 * [declaredBy] is shared between the response walk and the request walk, so two
 * classes that would generate the same TypeScript name fail wherever the second
 * one is first reached.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class DescriptorWalk(
    private val optionality: Optionality,
    private val declaredBy: MutableMap<String, String>,
) {
    val interfaces: MutableMap<String, TsInterface> = LinkedHashMap()
    val enums: MutableMap<String, TsEnum> = LinkedHashMap()
    private val inProgress = HashSet<String>()

    /** Walks [descriptor] and everything it reaches; returns its TypeScript spelling. */
    fun typeOf(descriptor: SerialDescriptor): String {
        val target = descriptor.nonNullOriginal
        embeddedJsonType(target)?.let { return it }
        return when (val kind = target.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> TS_STRING
            PrimitiveKind.BOOLEAN -> TS_BOOLEAN
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT,
            PrimitiveKind.LONG, PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE,
            -> TS_NUMBER
            StructureKind.LIST -> "${typeOf(target.getElementDescriptor(0))}[]"
            StructureKind.MAP -> "Record<string, ${typeOf(target.getElementDescriptor(MAP_VALUE_ELEMENT))}>"
            SerialKind.ENUM -> enumType(target)
            StructureKind.CLASS, StructureKind.OBJECT -> interfaceType(target)
            is PolymorphicKind ->
                throw ApiTypeGenerationException(
                    "${target.serialName} is sealed or polymorphic. Nothing on the wire is today, and " +
                        "the discriminator mapping is undesigned; design it before serving this type.",
                )
            else ->
                throw ApiTypeGenerationException(
                    "${target.serialName} has serial kind $kind, which has no TypeScript mapping.",
                )
        }
    }

    private fun interfaceType(descriptor: SerialDescriptor): String {
        val name = claimName(descriptor.serialName)
        if (interfaces.containsKey(descriptor.serialName)) return name
        // A self-referential type would recurse forever; the name is enough for the cycle.
        if (!inProgress.add(descriptor.serialName)) return name
        val fields =
            (0 until descriptor.elementsCount).map { index ->
                TsField(
                    name = descriptor.getElementName(index),
                    type = typeOf(descriptor.getElementDescriptor(index)),
                    optional = isOptional(descriptor, index),
                )
            }
        inProgress.remove(descriptor.serialName)
        interfaces[descriptor.serialName] = TsInterface(name, fields)
        return name
    }

    private fun enumType(descriptor: SerialDescriptor): String {
        val name = claimName(descriptor.serialName)
        enums.getOrPut(descriptor.serialName) {
            TsEnum(name, (0 until descriptor.elementsCount).map(descriptor::getElementName))
        }
        return name
    }

    /**
     * The encoder is `encodeDefaults = true, explicitNulls = false`: a response
     * omits exactly the nulls, so only a nullable field is optional. A request is
     * decoded, so a default also makes the element absent-tolerant.
     */
    private fun isOptional(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        val nullable = descriptor.getElementDescriptor(index).isNullable
        return when (optionality) {
            Optionality.RESPONSE -> nullable
            Optionality.REQUEST -> nullable || descriptor.isElementOptional(index)
        }
    }

    private fun claimName(serialName: String): String {
        val name = tsName(serialName)
        val owner = declaredBy.putIfAbsent(name, serialName)
        if (owner != null && owner != serialName) {
            throw ApiTypeGenerationException(
                "Two classes would generate the TypeScript name '$name': $owner and $serialName. " +
                    "Rename one, or give it a @SerialName that disambiguates.",
            )
        }
        return name
    }

    private fun embeddedJsonType(descriptor: SerialDescriptor): String? {
        if (!descriptor.serialName.startsWith(JSON_PACKAGE)) return null
        return when (descriptor.serialName) {
            JSON_ELEMENT -> TS_UNKNOWN
            JSON_OBJECT -> TS_UNKNOWN_RECORD
            JSON_ARRAY -> TS_UNKNOWN_ARRAY
            JSON_PRIMITIVE -> TS_JSON_PRIMITIVE
            else ->
                throw ApiTypeGenerationException(
                    "${descriptor.serialName} is a kotlinx.serialization.json type with no TypeScript " +
                        "mapping. JsonElement, JsonObject, JsonArray and JsonPrimitive are the four that map.",
                )
        }
    }
}
```

- [ ] **Step 5: Add the generator**

Create `backend/src/main/kotlin/ca/floo/roadtrip/apigen/ApiTypeGenerator.kt`:

```kotlin
package ca.floo.roadtrip.apigen

import ca.floo.roadtrip.model.api.ApiContract
import ca.floo.roadtrip.model.api.ApiEndpoint
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.full.createType

private const val GENERATED_HEADER =
    """// Generated by `./gradlew :backend:generateApiTypes` (`make api-types`) from the
// Kotlin @Serializable DTOs named in
// backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiContract.kt.
//
// Do not edit by hand. `make test` and CI run `:backend:checkApiTypes`, which
// fails when this file and the DTOs disagree.
//
// Optionality follows the one encoder (route/common/RouteResponses.kt:
// encodeDefaults = true, explicitNulls = false). In a response body a field is
// optional exactly when it is nullable, and a non-null field with a default is
// always sent; in a request body a field is optional when it is nullable or has
// a default. `| null` never appears, because null is never encoded.
//
// Every Kotlin integer is a TypeScript `number`: the ids are database bigints
// that stay well below 2^53.
"""

@Suppress("TopLevelPropertyNaming")
private val IDENTIFIER = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")

/**
 * The whole generated file, for [endpoints]. Deterministic: declarations sorted
 * by name, fields in declaration order, endpoints sorted by path then method.
 */
internal fun generateApiTypes(endpoints: List<ApiEndpoint> = ApiContract.endpoints): String {
    val declaredBy = HashMap<String, String>()
    val responses = DescriptorWalk(Optionality.RESPONSE, declaredBy)
    val requests = DescriptorWalk(Optionality.REQUEST, declaredBy)
    endpoints.forEach { endpoint ->
        (listOfNotNull(endpoint.response) + endpoint.errors).forEach { responses.typeOf(it.descriptor()) }
        endpoint.request?.let { requests.typeOf(it.descriptor()) }
    }
    return render(
        interfaces = merge(responses.interfaces, requests.interfaces),
        enums = (responses.enums + requests.enums).values.toList(),
        endpoints = endpointRows(endpoints),
    )
}

private fun KClass<*>.descriptor(): SerialDescriptor = serializer(createType()).descriptor

/**
 * One interface per class. A class both sides reach must describe the same
 * shape under both optionality rules; when it stops doing so the build says
 * which fields disagree, and whoever needs the second variant designs it then.
 */
private fun merge(
    responses: Map<String, TsInterface>,
    requests: Map<String, TsInterface>,
): List<TsInterface> {
    responses.keys.intersect(requests.keys).forEach { serialName ->
        val fromResponse = responses.getValue(serialName)
        val fromRequest = requests.getValue(serialName)
        if (fromResponse == fromRequest) return@forEach
        val differing =
            fromResponse.fields
                .zip(fromRequest.fields)
                .filter { (response, request) -> response != request }
                .joinToString { (response, _) -> response.name }
        throw ApiTypeGenerationException(
            "$serialName is both a request body and a response body, and the two optionality rules " +
                "disagree on: $differing. Split it into two DTOs, or give the request field the same " +
                "nullability the response has.",
        )
    }
    return (responses + requests).values.sortedBy { it.name }
}

private fun endpointRows(endpoints: List<ApiEndpoint>): List<TsEndpoint> =
    endpoints
        .map { endpoint ->
            TsEndpoint(
                method = endpoint.method.wireValue,
                path = endpoint.path,
                request = endpoint.request?.let { tsName(it.descriptor().serialName) },
                response = endpoint.response?.let { tsName(it.descriptor().serialName) },
            )
        }.sortedWith(compareBy({ it.path }, { it.method }))

private fun render(
    interfaces: List<TsInterface>,
    enums: List<TsEnum>,
    endpoints: List<TsEndpoint>,
): String =
    buildString {
        append(GENERATED_HEADER)
        (enums.map { it.name to renderEnum(it) } + interfaces.map { it.name to renderInterface(it) })
            .sortedBy { it.first }
            .forEach { append("\n").append(it.second) }
        append("\n").append(renderEndpoints(endpoints))
    }

private fun renderEnum(tsEnum: TsEnum): String =
    "export type ${tsEnum.name} = ${tsEnum.values.joinToString(" | ") { "'$it'" }};\n"

private fun renderInterface(tsInterface: TsInterface): String =
    buildString {
        append("export interface ${tsInterface.name} {\n")
        tsInterface.fields.forEach { field ->
            append("  ${key(field.name)}${if (field.optional) "?" else ""}: ${field.type};\n")
        }
        append("}\n")
    }

private fun renderEndpoints(endpoints: List<TsEndpoint>): String =
    buildString {
        append("export const API_ENDPOINTS = [\n")
        endpoints.forEach { endpoint ->
            append("  { method: '${endpoint.method}', path: '${endpoint.path}'")
            append(", request: ${quoted(endpoint.request)}")
            append(", response: ${quoted(endpoint.response)} },\n")
        }
        append("] as const;\n")
    }

private fun quoted(name: String?): String = name?.let { "'$it'" } ?: "null"

/** A wire key that is not a bare TypeScript identifier has to be quoted. */
private fun key(name: String): String = if (IDENTIFIER.matches(name)) name else "'$name'"
```

- [ ] **Step 6: Add the entry point**

Create `backend/src/main/kotlin/ca/floo/roadtrip/apigen/GenerateApiTypes.kt`:

```kotlin
package ca.floo.roadtrip.apigen

import java.io.File
import kotlin.system.exitProcess

private const val CHECK_FLAG = "--check"
private const val USAGE = "usage: GenerateApiTypes <path/to/api-types.ts> [--check]"
private const val DRIFT_EXIT_CODE = 1

/**
 * Writes the generated TypeScript, or — with `--check` — compares it against
 * what is committed and fails when they differ. `:backend:generateApiTypes` and
 * `:backend:checkApiTypes` are the two Gradle faces of this one entry point.
 */
fun main(args: Array<String>) {
    val target = args.firstOrNull() ?: error(USAGE)
    val generated = generateApiTypes()
    val file = File(target)
    if (!args.drop(1).contains(CHECK_FLAG)) {
        file.parentFile?.mkdirs()
        file.writeText(generated)
        println("wrote ${file.path}")
        return
    }
    if (file.exists() && file.readText() == generated) {
        println("${file.path} is up to date")
        return
    }
    System.err.println(
        "$target is stale: the committed API types no longer match the Kotlin DTOs. " +
            "Run `make api-types` and commit the diff.",
    )
    exitProcess(DRIFT_EXIT_CODE)
}
```

- [ ] **Step 7: Run the generator tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.apigen.ApiTypeGeneratorTest' --offline -q`
Expected: PASS, all ten cases. `the real contract generates the types this phase exists for`
exercises the whole contract, so a DTO the walk cannot express fails here first, naming itself.

- [ ] **Step 8: Register the two Gradle tasks**

In `backend/build.gradle.kts`, after the `installPlaywrightBrowsers` registration, add:

```kotlin
// The TypeScript API contract. Both tasks run the same generator over `main`'s
// runtime classpath, so both inherit `main`'s compile — which means generateJooq,
// which means Docker, exactly like :backend:test. They belong in the backend-tests
// CI job and in `make test`; never in gradle-lint, which exists to run without codegen.
val generatedApiTypesFile = rootProject.file("frontend/src/api/generated/api-types.ts")
val apiGenMainClass = "ca.floo.roadtrip.apigen.GenerateApiTypesKt"

tasks.register<JavaExec>("generateApiTypes") {
    group = "build"
    description = "Write frontend/src/api/generated/api-types.ts from the Kotlin @Serializable DTOs."
    mainClass.set(apiGenMainClass)
    classpath = sourceSets["main"].runtimeClasspath
    args(generatedApiTypesFile.absolutePath)
}

tasks.register<JavaExec>("checkApiTypes") {
    group = "verification"
    description = "Fail when the committed api-types.ts no longer matches the Kotlin @Serializable DTOs."
    mainClass.set(apiGenMainClass)
    classpath = sourceSets["main"].runtimeClasspath
    args(generatedApiTypesFile.absolutePath, "--check")
}
```

In the same file's `kover { reports { filters { excludes { classes( … ) } } } }` block, add the
entry point beside the two that are already there:

```kotlin
                classes(
                    "ca.floo.roadtrip.MainKt",
                    "ca.floo.roadtrip.RoadtripRoutingKt*",
                    // Build tooling, not served code: the generator's logic is covered by
                    // ApiTypeGeneratorTest; only this argv-and-exit wrapper is not.
                    "ca.floo.roadtrip.apigen.GenerateApiTypesKt",
                )
```

- [ ] **Step 9: Generate and inspect the file**

Run: `./gradlew :backend:generateApiTypes --offline -q`
Expected: `wrote …/frontend/src/api/generated/api-types.ts`.

Run: `head -20 frontend/src/api/generated/api-types.ts && grep -c '^export interface' frontend/src/api/generated/api-types.ts && grep '^export type' frontend/src/api/generated/api-types.ts`
Expected: the header comment; well over eighty interfaces; and these ten unions, the only
enums any contracted DTO field is typed with — `AddToCartState`, `AvailabilityStatus`,
`AvailabilityWindowState`, `BookingActionStatus`, `ReadinessResponseDtoDependency`,
`ReadinessResponseDtoState`, `RecgovLoginStatus`, `RecgovSessionState`, `WatchDoneReason`,
`WatchStatus`. (`AmenityKey`, `Carrier`, `CampsiteKind`, `RunKind` and `RunStatus` are all
mapped to `String` by their DTOs, so none of them appears; that is today's wire and this phase
does not change it.)

- [ ] **Step 10: Prove the check catches drift**

```bash
printf '\n// hand edit\n' >> frontend/src/api/generated/api-types.ts
./gradlew :backend:checkApiTypes --offline -q; echo "exit=$?"
```
Expected: the stale message on stderr and `exit=1`.

```bash
./gradlew :backend:generateApiTypes --offline -q
./gradlew :backend:checkApiTypes --offline -q; echo "exit=$?"
```
Expected: `… is up to date` and `exit=0`.

Now the same for a DTO change, which is what the gate is really for:

```bash
sed -i '' 's/    val sent: Boolean,/    val sent: Boolean,\n    val scratch: String? = null,/' \
  backend/src/main/kotlin/ca/floo/roadtrip/model/api/EmailTestResponseDto.kt
./gradlew :backend:checkApiTypes --offline -q; echo "exit=$?"
```
Expected: the stale message and `exit=1`.

```bash
git checkout -- backend/src/main/kotlin/ca/floo/roadtrip/model/api/EmailTestResponseDto.kt
./gradlew :backend:checkApiTypes --offline -q; echo "exit=$?"
```
Expected: `exit=0`.

- [ ] **Step 11: Prove the generated file typechecks on its own**

Run: `cd frontend && npx tsc --noEmit src/api/generated/api-types.ts`
Expected: no output. (Nothing imports it yet; Tasks 5 and 6 do.)

- [ ] **Step 12: Run the full gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt :backend:checkApiTypes --offline -q`
Expected: PASS.

Run: `cd frontend && npm run typecheck && npm run test && npm run lint`
Expected: PASS. The generated file is inside `src/api/`, which
`scripts/check-feature-boundaries.mjs` does not govern — it only walks `src/features/` and
`src/domain/` — so the new directory cannot violate the boundary rule.

- [ ] **Step 13: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/apigen \
        backend/src/test/kotlin/ca/floo/roadtrip/apigen \
        backend/build.gradle.kts \
        frontend/src/api/generated/api-types.ts
git commit -F - <<'MSG'
feat(api): TypeScript types are generated from the DTO descriptors and committed

ca.floo.roadtrip.apigen walks serializer(kclass).descriptor from every ApiContract
row and writes frontend/src/api/generated/api-types.ts. Optionality follows the
one encoder rather than the Kotlin type: a response field is optional exactly when
it is nullable, a request field also when it has a default, and `| null` is never
emitted. Sealed types, name collisions and a shared class whose two optionality
rules disagree all fail generation by name.

:backend:generateApiTypes writes the file, :backend:checkApiTypes fails when it
drifts. Both run over main's runtime classpath, so both need Docker for
generateJooq, exactly like :backend:test.

Refs #743

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---
### Task 5: The frontend adopts the generated types — everything but `poi-api.ts`

Eleven client modules stop declaring mirrors and start aliasing the generated types. Every
exported name survives, so none of the seventy-six importers change. The two dead
`watch-triggers.ts` casts go, and `alert-rows.ts` reads `done_reason`.

**Files:**
- Modify: `frontend/src/api/campsite-api.ts`, `frontend/src/api/availability-api.ts`, `frontend/src/api/watches-api.ts`, `frontend/src/api/account-api.ts`, `frontend/src/api/auth-api.ts`, `frontend/src/api/booking-api.ts`, `frontend/src/api/availability-dashboard-api.ts`, `frontend/src/api/geocode-api.ts`, `frontend/src/api/password-auth-api.ts`, `frontend/src/api/sandbox-api.ts`, `frontend/src/lib/watch-triggers.ts`, `frontend/src/features/alerts/alert-rows.ts`, `frontend/src/features/alerts/AlertsPanel.tsx`, `frontend/src/features/availability/AvailabilityWeek.tsx`
- Test: `frontend/src/lib/watch-triggers.test.ts`, `frontend/src/features/alerts/alert-rows.test.ts`

**Interfaces:**
- Consumes: every `export interface` and `export type` in `frontend/src/api/generated/api-types.ts` (Task 4).
- Produces: the same exported names the eleven modules export today, now aliases — plus one new helper, `frontend/src/api/availability-api.ts` → `export function seasonReopensOn(season: unknown): string | undefined`.
- Removed: `SeasonBlock` from `availability-api.ts` (nothing outside that file imports it), and the `doneKind` return type widens from `'expired' | 'found'` to the same two values read from the wire.

#### The alias map

Same name on both sides means a re-export (`export type { X } from './generated/api-types';`),
because `export type X = X;` is circular. Everything else is
`import type { … } from './generated/api-types';` plus `export type Local = GeneratedDto;`.

| Module | Local name | Generated name |
|---|---|---|
| `campsite-api.ts` | `CampsiteAttribute` | `CampsiteAttribute` (re-export) |
| | `Campsite` | `CampsiteDto` |
| | `PoiCampsitesResponse` | `PoiCampsitesResponseSchema` |
| `availability-api.ts` | `AvailabilityWindowState` | `AvailabilityWindowState` (re-export) |
| | `AddToCartState` | `AddToCartState` (re-export) |
| | `AvailabilityCell` | `AvailabilityCellDto` |
| | `AvailabilityDay` | `AvailabilityDayDto` |
| | `AvailabilityCache` | `AvailabilityCacheBlock` |
| | `WatchCapabilities` | `AvailabilityWatchCapabilitiesDto` |
| | `PoiCampsitesAvailabilityResponse` | `PoiCampsitesAvailabilityResponseDto` |
| `watches-api.ts` | `WatchStatus` | `WatchStatus` (re-export) |
| | `WatchTarget` | `AvailabilityWatchTargetSchema` |
| | `Watch` | `AvailabilityWatchSchema` |
| | `WatchListResponse` | `AvailabilityWatchListResponse` |
| | `WatchResponse` | `AvailabilityWatchResponse` |
| | `CreateWatchRequest` | `AvailabilityWatchCreateRequest` |
| | `UpdateWatchRequest` | `AvailabilityWatchUpdateRequest` |
| `account-api.ts` | `Profile` | `ProfileDto` |
| | `Notifications` | `NotificationsDto` |
| | `BookingSettings` | `BookingSettingsDto` |
| | `SettingsResponse` | `SettingsResponseDto` |
| | `RecgovStatus` | `RecgovStatusDto` |
| | `RecgovLoginResponse` | `RecgovLoginResponseDto` |
| | `RecgovVerifyResponse` | `RecgovVerifyResponseDto` |
| | `RecgovRemovedResponse` | `RecgovRemovedDto` |
| | `UpdateBookingFields` | `UpdateRecgovRequest` |
| | `SlackTestResponse` | `SlackTestResponseDto` |
| | `EmailTestResponse` | `EmailTestResponseDto` |
| | `UpdateNotificationsFields` | `UpdateNotificationsRequest` |
| `auth-api.ts` | `MeUser` | `MeUserDto` |
| | `Me` | `MeResponseDto` |
| `booking-api.ts` | `AddToCartFields` | `AddToCartRequestDto` |
| | `AddToCartResponse` | `AddToCartResponseDto` |
| | `AddToCartFailure` | `ApiErrorSchema` |
| `availability-dashboard-api.ts` | `AvailabilityPoller` | `AvailabilityPollerSchema` |
| | `PollersListResponse` | `AvailabilityPollersListResponse` |
| | `PollersSummary` | `AvailabilityPollersSummary` |
| | `AvailabilityRun` | `AvailabilityRunSchema` |
| | `RunsListResponse` | `AvailabilityRunsListResponse` |
| | `AvailabilityChange` | `AvailabilityChangeSchema` |
| | `ChangesListResponse` | `ListAvailabilityChangesResponse` |
| | `SnapshotStats` | `AvailabilitySnapshotStatsSchema` |
| | `ChangesSummaryResponse` | `AvailabilitySnapshotsSummaryResponse` |
| | `ForcePollerAccepted` | `CheckNowResponseDto` |
| | `ForcePollerCooldown` | `CheckNowCooldownDto` |
| `geocode-api.ts` | `GeocodeResult` | `GeocodeResultDto` |
| | `GeocodeResponse` | `GeocodeResponseDto` |
| `password-auth-api.ts` | `PasswordBeginResponse` | `PasswordBeginResponseDto` |
| `sandbox-api.ts` | `BuildInfo` | `BuildInfoDto` |

`ForcePollerResult`, every `*Params` / `*Options` / `*Fields` interface that extends
`RequestOptions`, and `directions-api.ts` in full are frontend-only shapes with no DTO behind
them. They stay exactly as they are.

- [ ] **Step 1: Write the failing `alert-rows` tests**

In `frontend/src/features/alerts/alert-rows.test.ts`, replace the `doneKind` describe block
with:

```ts
describe('doneKind', () => {
  it('reads the recorded reason', () => {
    expect(doneKind(watch({ done_reason: 'elapsed', end_date: '2026-08-20' }), '2026-08-09')).toBe('expired');
    expect(doneKind(watch({ done_reason: 'triggered', end_date: '2026-08-01' }), '2026-08-09')).toBe('found');
  });

  it('does not mislabel a watch triggered on its last day', () => {
    expect(doneKind(watch({ done_reason: 'triggered', end_date: '2026-08-09' }), '2026-08-09')).toBe('found');
  });

  it('falls back to the end date for a pre-migration row', () => {
    expect(doneKind(watch({ end_date: '2026-08-01' }), '2026-08-09')).toBe('expired');
    expect(doneKind(watch({ end_date: '2026-08-20' }), '2026-08-09')).toBe('found');
    expect(doneKind(watch({ end_date: undefined }), '2026-08-09')).toBe('found');
  });
});
```

- [ ] **Step 2: Delete the camelCase cases from `watch-triggers.test.ts`**

Remove these two cases outright — the backend has only ever emitted snake_case, and the
payloads they describe are manufactured by the test itself:

```ts
  test('reads the camelCase form the availability week used internally', () => {
    const w = { triggerConfig: { slack_notify: { channel: '#camel' } } } as unknown as Partial<Watch>;
    expect(watchSlackChannel(w)).toBe('#camel');
  });
```

```ts
  test('reads the camelCase form', () => {
    const w = { stopWhenTriggered: false } as unknown as Partial<Watch>;
    expect(watchStopWhenTriggered(w)).toBe(false);
  });
```

- [ ] **Step 3: Run the two suites to verify they fail**

Run: `cd frontend && npx vitest run src/features/alerts/alert-rows.test.ts src/lib/watch-triggers.test.ts`
Expected: `alert-rows.test.ts` FAILS — `doneKind` ignores `done_reason`, so
`done_reason: 'elapsed'` with a future `end_date` returns `'found'`, and
`done_reason: 'triggered'` with a past one returns `'expired'`. `watch-triggers.test.ts`
PASSES (the deletions removed coverage, not behaviour) — its implementation change lands in
Step 6.

- [ ] **Step 4: Rewrite the ten client modules**

Each module loses its mirror interfaces and their "Mirrors …" comments, and gains one import
block plus aliases. `campsite-api.ts` in full, as the pattern:

```ts
import type { CampsiteDto, PoiCampsitesResponseSchema } from './generated/api-types';
import { jsonGetOk, type RequestOptions } from './http';

export type { CampsiteAttribute } from './generated/api-types';

/**
 * The campsite drawer's closed type. Generated from `CampsiteDto`, so a field the
 * backend adds is a field the drawer can read, and a field it renames is a
 * typecheck failure here.
 */
export type Campsite = CampsiteDto;

export type PoiCampsitesResponse = PoiCampsitesResponseSchema;

export function fetchPoiCampsites(
  poiId: number | string,
  { signal }: RequestOptions = {},
): Promise<PoiCampsitesResponse> {
  return jsonGetOk<PoiCampsitesResponse>(poiCampsitesUrl(poiId), { signal });
}

export function poiCampsitesUrl(poiId: number | string): string {
  return `/api/pois/${encodeURIComponent(String(poiId))}/campsites`;
}
```

`availability-api.ts` additionally loses `SeasonBlock` and gains the narrowing helper, because
the wire carries the provider's season block unparsed (`JsonElement` → `unknown`):

```ts
import type {
  AddToCartState,
  AvailabilityCacheBlock,
  AvailabilityCellDto,
  AvailabilityDayDto,
  AvailabilityWatchCapabilitiesDto,
  AvailabilityWindowState,
  PoiCampsitesAvailabilityResponseDto,
} from './generated/api-types';

export type { AddToCartState, AvailabilityWindowState } from './generated/api-types';

export type AvailabilityCell = AvailabilityCellDto;
export type AvailabilityDay = AvailabilityDayDto;
export type AvailabilityCache = AvailabilityCacheBlock;
export type WatchCapabilities = AvailabilityWatchCapabilitiesDto;
export type PoiCampsitesAvailabilityResponse = PoiCampsitesAvailabilityResponseDto;

/**
 * The provider's season block rides the wire unparsed, so `reopens_on` is
 * narrowed here rather than asserted at the one call site that reads it.
 */
export function seasonReopensOn(season: unknown): string | undefined {
  if (!season || typeof season !== 'object') return undefined;
  const value = (season as Record<string, unknown>).reopens_on;
  return typeof value === 'string' ? value : undefined;
}
```

Note that `AddToCartState` and `AvailabilityWindowState` appear in both the `import type` block
(the module's own code references them) and the re-export.

Apply the same shape to `watches-api.ts`, `account-api.ts`, `auth-api.ts`, `booking-api.ts`,
`availability-dashboard-api.ts`, `geocode-api.ts`, `password-auth-api.ts` and
`sandbox-api.ts`, following the alias map above. Do not touch the functions in any of them.

- [ ] **Step 5: Typecheck and fix every error at its use site**

Run: `cd frontend && npm run typecheck`

Fix each error where it is raised, never by widening the generated type. The four rules:
absent is `undefined`, never `null`; a field the server always sends is not optional; a field
the server may omit is optional; an unparsed blob is `unknown` and gets narrowed.

The errors this change is expected to raise, and their fixes:

1. `src/features/availability/AvailabilityWeek.tsx:641` —
   `const reopens = week.data.season?.reopens_on;` no longer typechecks, because `season` is
   `unknown`. Replace with:

   ```tsx
       const reopens = seasonReopensOn(week.data.season);
   ```

   and add `seasonReopensOn` to the file's existing `@/api/availability-api` import.

2. Anywhere a component compared an optional wire field to `null` — `x === null`,
   `x !== null`, `x ?? …` guarded by a null check. `explicitNulls = false` means the key is
   absent, so the comparison becomes `x === undefined` / `x == null` (which catches both) or
   simply `!x`.

3. Anywhere a fixture or a caller omitted a field that is now required — `provider_display` on
   `AddToCartResponse`, `data_provider` / `data_provider_ref` on `Campsite`. Add the field to
   the fixture with the value the server would send.

4. Anywhere a fixture supplied a field that no longer exists under that name. The generated
   interface carries the server's own key; rename the fixture key.

Re-run `npm run typecheck` until it is clean. Do not add `as` casts to silence an error: each
one is drift the compiler just found, which is the point of the phase.

- [ ] **Step 6: Delete the two dead casts in `watch-triggers.ts`**

Replace:

```ts
function triggerConfigOf(watch: Partial<Watch> | null | undefined): Record<string, unknown> {
  const config =
    watch?.trigger_config ?? (watch as { triggerConfig?: unknown } | null)?.triggerConfig;
  return (config ?? {}) as Record<string, unknown>;
}
```

with:

```ts
function triggerConfigOf(watch: Partial<Watch> | null | undefined): Record<string, unknown> {
  return watch?.trigger_config ?? {};
}
```

and replace:

```ts
  const value =
    watch.stop_when_triggered ??
    (watch as { stopWhenTriggered?: unknown } | null)?.stopWhenTriggered;
  return value == null ? fallback : Boolean(value);
```

with:

```ts
  return watch.stop_when_triggered ?? fallback;
```

The top-level `channel` fallback in `watchSlackChannel` is a real legacy shape and stays,
comment included. If the file declares its own local `Watch` shape at the top, delete it and
import the aliased one: `import type { Watch } from '@/api/watches-api';`.

- [ ] **Step 7: Read the recorded done reason**

In `frontend/src/features/alerts/alert-rows.ts`, replace `doneKind` and its comment:

```ts
/**
 * Why a watch is done: availability was found, or its window elapsed.
 *
 * Read from `done_reason`, which the two done-transitions record since V63. The
 * end-date inference is the fallback for a row retired before that migration —
 * it mislabels a watch triggered on its last day, which is exactly why the
 * column exists. `today` is a parameter so a test does not have to mock the clock.
 */
export function doneKind(watch: Watch, today: string = new Date().toISOString().slice(0, 10)) {
  if (watch.done_reason === 'triggered') return 'found' as const;
  if (watch.done_reason === 'elapsed') return 'expired' as const;
  const end = watch.end_date ?? '';
  return end && end < today ? 'expired' : ('found' as const);
}
```

`AlertsPanel.tsx:191` already reads `doneKind(watch) === 'expired'` and needs no change.

- [ ] **Step 8: Run the two suites to verify they pass**

Run: `cd frontend && npx vitest run src/features/alerts/alert-rows.test.ts src/lib/watch-triggers.test.ts`
Expected: PASS.

- [ ] **Step 9: Check that no mirror survives in the ten modules**

Run: `cd frontend && grep -rn "Mirrors" src/api/*.ts | grep -v poi-api.ts`
Expected: no output.

- [ ] **Step 10: Run the frontend gate**

Run: `cd frontend && npm run typecheck && npm run test && npm run lint`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add frontend/src/api/campsite-api.ts frontend/src/api/availability-api.ts \
        frontend/src/api/watches-api.ts frontend/src/api/account-api.ts \
        frontend/src/api/auth-api.ts frontend/src/api/booking-api.ts \
        frontend/src/api/availability-dashboard-api.ts frontend/src/api/geocode-api.ts \
        frontend/src/api/password-auth-api.ts frontend/src/api/sandbox-api.ts \
        frontend/src/lib/watch-triggers.ts frontend/src/lib/watch-triggers.test.ts \
        frontend/src/features/alerts/alert-rows.ts frontend/src/features/alerts/alert-rows.test.ts \
        frontend/src/features/availability frontend/src/features/alerts
git commit -F - <<'MSG'
refactor(frontend): ten API clients alias the generated types instead of mirroring them

Every exported name survives as an alias or a re-export, so no importer changes;
what goes is forty-odd hand-written interfaces and the "Mirrors XDto" comments
above them. The compiler surfaced the drift the mirrors were hiding, and each
error is fixed at its use site.

watch-triggers.ts loses the triggerConfig and stopWhenTriggered casts — the
backend has only ever emitted snake_case, and the camelCase payloads were
manufactured by the tests that asserted them. alert-rows.doneKind reads
done_reason and keeps the end-date guess only for rows retired before V63.

Refs #743

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

### Task 6: `poi-api.ts` and the campground drawer

`poi-api.ts` never had mirrors at all — it is where the real drift lives: a search hit that
types `id` as `number | string` and marks four required server fields optional, no `region`,
and a `CampgroundDetail` that declares nine of `PoiCategoryDetailSchema`'s fifty-five fields
with a cast papering over the rest.

**Files:**
- Modify: `frontend/src/api/poi-api.ts`, `frontend/src/domain/poi/campground-detail.ts:27`
- Test: `frontend/src/api/poi-api.test.ts` and whatever the typecheck names

**Interfaces:**
- Consumes: `AlertDto`, `AmenityDto`, `CarrierSignalDto`, `PoiCategoryDetailSchema`, `PoiSearchHitSchema`, `PoiSearchResponseSchema`, `PriceDto`, `RatingDto`, `ScheduleDto`, `SlimPoiPropertiesSchema` from `./generated/api-types`.
- Produces: the same exported names `poi-api.ts` exports today. `PoiPinFeature`, `PoiPinCollection` and `ViewportPoiCollection` keep their `geojson` generics; only their properties type is generated.

- [ ] **Step 1: Replace the hand-written types**

In `frontend/src/api/poi-api.ts`, delete `PoiSearchResult`, `PoiSearchResponse`,
`PoiPinProperties`, the six `*Dto` interfaces, `CampgroundDetail`, and the
"One mirror per DTO in `model/api/poi/`" comment block above them. In their place:

```ts
import type {
  PoiCategoryDetailSchema,
  PoiSearchHitSchema,
  PoiSearchResponseSchema,
  SlimPoiPropertiesSchema,
} from './generated/api-types';

export type { AlertDto, AmenityDto, CarrierSignalDto, PriceDto, RatingDto, ScheduleDto } from './generated/api-types';

/**
 * A POI as the search endpoints return it. Closed: `id` is the row's `pois.id`,
 * and `region` is the one the dropdown shows beside the name.
 */
export type PoiSearchResult = PoiSearchHitSchema;

export type PoiSearchResponse = PoiSearchResponseSchema;

/**
 * The properties a pin carries on the two FeatureCollection endpoints. This is
 * the whole payload: no name, no address, nothing per-provider. Those are fetched
 * on click through `GET /api/pois/{id}`, which is why the map's drawer hydrates
 * by id rather than reading what it was handed.
 */
export type PoiPinProperties = SlimPoiPropertiesSchema;

/**
 * A campground's served detail bag, in full. The backend owns both the shape and
 * the words — `label` on an amenity or a carrier is render-ready — so the
 * frontend keeps no vocabulary of its own.
 */
export type CampgroundDetail = PoiCategoryDetailSchema;
```

`PoiPinFeature`, `PoiPinCollection`, `ViewportPoiCollection`, `PoiSearchUrlParams`,
`SearchPoisOptions`, `ViewportPoisParams` and `OnRoutePoisParams` are unchanged: the first
three are `geojson` generics the map libraries require, and the rest are call-site parameter
bags with no DTO behind them.

- [ ] **Step 2: Point the campground drawer's one assertion at the full schema**

In `frontend/src/domain/poi/campground-detail.ts`, replace the `typed` helper and its KDoc:

```ts
/**
 * The typed view of a hydrated POI's campground bag.
 *
 * `flattenHydratedPoi` is open on purpose — most of a hydrated POI is still
 * whatever the vendor sent — and an open bag cannot be narrowed without an
 * assertion. What changed is what it asserts to: `CampgroundDetail` is now
 * `PoiCategoryDetailSchema` in full, so every field a drawer row reads is the
 * server's own, and a wire rename is a typecheck failure here instead of a row
 * that silently stops rendering. `Partial` stays because the schema has fields
 * the server always sends but a flattened bag need not carry.
 */
const typed = (p: Props): Partial<CampgroundDetail> => p as Partial<CampgroundDetail>;
```

- [ ] **Step 3: Typecheck and fix every error at its use site**

Run: `cd frontend && npm run typecheck`

The errors this raises, and their fixes — same four rules as Task 5, Step 5:

1. Callers that read a search hit's `id` as a string (`String(row.id)`, or passing it where a
   string is wanted). `PoiSearchHitSchema.id` is a `number`; keep the `String(...)` where a URL
   segment is being built and drop any `typeof row.id === 'string'` branch.
2. Callers that guarded `row.name`, `row.category`, `row.lng` or `row.lat` as possibly
   undefined. All four are required on the wire; delete the guard rather than keep a branch
   that can never run.
3. Callers that indexed a search hit with an arbitrary key — the old type had
   `[key: string]: unknown`. The generated type is closed; read the field by name, or, if the
   value genuinely is not on the search hit, fetch it through `GET /api/pois/{id}`.
4. `campground-detail.ts` readers whose field name does not exist on
   `PoiCategoryDetailSchema`. The schema's name is the wire name; rename the reader.

Re-run until clean. No `as` casts beyond the one `typed()` above.

- [ ] **Step 4: Run the frontend gate**

Run: `cd frontend && npm run typecheck && npm run test && npm run lint`
Expected: PASS.

- [ ] **Step 5: Confirm the mirrors are gone repo-wide**

Run: `cd frontend && grep -rn "Mirrors" src/api/`
Expected: no output.

Run: `cd frontend && grep -rn "interface" src/api/*.ts | grep -v "Params\|Options\|Fields\|ForcePollerResult\|ViewportPoiCollection\|RequestOptions"`
Expected: no output — every remaining `interface` in the clients is a call-site parameter bag
or one of the two documented frontend-only shapes.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/poi-api.ts frontend/src/domain/poi/campground-detail.ts \
        frontend/src frontend/src/features
git commit -F - <<'MSG'
refactor(frontend): poi-api and the campground drawer read the generated schema

poi-api.ts never mirrored anything, which is where the real drift was: the search
hit typed id as `number | string`, marked four required server fields optional and
omitted region, and CampgroundDetail declared nine of PoiCategoryDetailSchema's
fifty-five fields. Both are now the generated type, and the compile errors that
surfaced are fixed at their use sites.

campground-detail.ts keeps its single assertion — an open bag cannot be narrowed
without one — but asserts to the full schema, so every drawer row is typechecked
against the server's own field names. The GeoJSON generics stay: MapLibre needs
Feature<Point, P>, and only P is generated.

Refs #743

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

### Task 7: The gate and the docs

**Files:**
- Modify: `Makefile:88-113`, `.github/workflows/ci.yml:35-44,403-404`, `AGENTS.md`, `docs/backend-architecture.md`, `docs/frontend-components.md:60`

**Interfaces:**
- Consumes: `:backend:checkApiTypes` and `:backend:generateApiTypes` (Task 4).
- Produces: a `make api-types` target; `checkApiTypes` in the `test` target and in the
  `backend-tests` CI job; `frontend/src/api/generated/**` in the `backend_tests` path filter.

- [ ] **Step 1: Wire the Makefile**

In `Makefile`, extend the `test` target's first line so the check shares the one Gradle
invocation:

```make
	./gradlew :backend:test :backend:koverXmlReport :backend:koverVerify :backend:ktlintCheck :backend:detekt :backend:checkApiTypes :detekt-rules:test
```

Extend the comment block above `test:` with one sentence after the paragraph that ends
"…would break a fresh clone.":

```make
# checkApiTypes rides along for the same reason: it runs the generator over main's
# runtime classpath, so it needs the same compile (and therefore the same Docker)
# :backend:test already needs. ci.yml runs it in backend-tests, never in gradle-lint.
```

Add the developer target immediately after the `test` target's last line:

```make
# Regenerate the committed TypeScript API contract after changing a DTO or adding
# a route. `make test` and CI fail when it is stale.
api-types:
	./gradlew :backend:generateApiTypes
```

and add `api-types` to the `.PHONY` list if the file keeps one.

- [ ] **Step 2: Wire CI**

In `.github/workflows/ci.yml`, add one line to the `backend_tests` filter, after
`- 'codecov.yml'`:

```yaml
              # A hand edit to the generated API types must run the backend job that
              # checks them, even in an otherwise frontend-only PR.
              - 'frontend/src/api/generated/**'
```

In the `backend-tests` job, extend the existing gradle step:

```yaml
      - name: Run backend tests + coverage + API contract check
        run: ./gradlew :backend:test :backend:koverXmlReport :backend:koverVerify :backend:checkApiTypes
```

Leave `gradle-lint` alone: it runs `ktlintCheck` with `-x :backend:generateJooq` precisely
because it has no reason to start a codegen container, and `checkApiTypes` would drag one in.

- [ ] **Step 3: Verify the two are still mirrors of each other**

Run: `grep -n 'checkApiTypes' Makefile .github/workflows/ci.yml`
Expected: one hit in the `Makefile` `test` recipe, one in the `backend-tests` job, and none in
`gradle-lint`.

- [ ] **Step 4: Add the AGENTS.md rule**

In `AGENTS.md`, add one bullet to the "Design principles" list, after the "Reusable components
and CSS" bullet:

```markdown
- **The TypeScript API types are generated.** `frontend/src/api/generated/api-types.ts` comes
  from the Kotlin `@Serializable` DTOs named in `model/api/ApiContract.kt`. Never edit it by
  hand. Adding a route means adding a contract row; changing a DTO means running
  `make api-types` and committing the diff. `make test` and CI fail when it is stale.
```

- [ ] **Step 5: Document the contract and the generator**

In `docs/backend-architecture.md`, add this section immediately before `## Adding Code`:

````markdown
## The API Contract

`model/api/ApiContract.kt` declares every endpoint under `/api/**` and
`/auth/password/**` as data: its method (an `ApiMethod`, because `model/` never
names Ktor), its path as registered, its request DTO, its 2xx response DTO, and
the error DTOs it can serialize. Two things read it.

`registerKoinRoutes` compares it against the live routing tree at boot, in both
directions, beside the RFC 0010 access guard. A mounted route with no row fails
the boot; a row with no route does too, unless the row is marked `conditional`
(the Slack interactivity endpoint, which mounts only when a signing secret is
configured). `/api/docs` is exempt: the Swagger UI subtree is framework-generated
and `openapi.json` answers with a Ktor type.

`:backend:generateApiTypes` walks `serializer(kclass).descriptor` from every row,
transitively, and writes `frontend/src/api/generated/api-types.ts`;
`:backend:checkApiTypes` runs the same generator and fails when the committed file
differs. Both go through `main`'s runtime classpath, so both need the same compile
— and therefore the same Docker for `generateJooq` — that `:backend:test` needs.
They run in `make test` and in CI's `backend-tests` job, never in `gradle-lint`.

Optionality follows the one encoder (`route/common/RouteResponses.kt`:
`encodeDefaults = true`, `explicitNulls = false`), not the Kotlin type:

- **Response:** a field is optional exactly when it is nullable. A non-null field
  with a default is always sent, so it is required. `| null` is never generated.
- **Request:** a field is optional when it is nullable **or** has a default.

A sealed or polymorphic DTO, two classes with the same generated name, and a class
used as both a request and a response whose two optionality rules disagree each
fail generation with a message naming the class.

The loop when you add or change an endpoint:

```
add the route  ->  add the ApiContract row  ->  make api-types  ->  commit the diff
```
````

- [ ] **Step 6: Rewrite the frontend "closed mirror" paragraph**

In `docs/frontend-components.md`, replace the paragraph at line 60:

```markdown
The `Campsite` type in `api/campsite-api.ts` is the closed mirror of `CampsiteDto`: every field is explicitly typed, with no index signature. When the drawer needs a new fact, the vendor ETL promotes it to a typed column and the DTO gains a field; nothing in the frontend reads a `source_payload` or reconciles vendor spellings. `features/availability/site-detail-facts.ts` is the one place campsite facts are turned into copy.
```

with:

```markdown
The `Campsite` type in `api/campsite-api.ts` is `CampsiteDto`, generated. Every type under `frontend/src/api/generated/` comes from the Kotlin `@Serializable` DTOs named in `model/api/ApiContract.kt`; `make test` and CI fail when the committed file and the DTOs disagree, so the closed type is closed by a build step rather than by hand. Never edit the generated file. When the drawer needs a new fact, the vendor ETL promotes it to a typed column and the DTO gains a field — and now nothing else: `make api-types` carries it to the frontend, and the compiler says which call sites can use it. The clients under `frontend/src/api/` keep their own exported names as aliases, so importers name `Campsite`, not `CampsiteDto`. Optional in a generated response type means the server omits the key; `null` never appears, because the one encoder never emits it. `features/availability/site-detail-facts.ts` is still the one place campsite facts are turned into copy.
```

- [ ] **Step 7: Check the docs say nothing that is no longer true**

Run: `grep -rn "Mirrors\|closed mirror\|hand-written" docs/frontend-components.md docs/backend-architecture.md AGENTS.md`
Expected: no hit that claims the frontend maintains the API types by hand.

- [ ] **Step 8: Run the whole gate, both halves**

Run: `make test`
Expected: PASS — backend tests, kover, ktlint, detekt, `checkApiTypes`, detekt-rule tests, the
full frontend half (this branch changes frontend code), the companion tests, the script and
secrets tooling, and the Grafana validation.

Then, with the stack up (`make run`):

Run: `make qa`
Expected: PASS. This is also the live exercise of the boot guard — the backend does not start
if `ApiContract` and the routing tree disagree.

- [ ] **Step 9: Commit**

```bash
git add Makefile .github/workflows/ci.yml AGENTS.md \
        docs/backend-architecture.md docs/frontend-components.md
git commit -F - <<'MSG'
docs: the contract, the generator, and the gate that keeps the generated types honest

make test and the backend-tests CI job run :backend:checkApiTypes; make api-types
regenerates. The backend_tests path filter gains
frontend/src/api/generated/**, so a hand edit to the generated file cannot slip
through a frontend-only PR. gradle-lint stays free of it, since that job exists to
run without the codegen container.

backend-architecture gains an API Contract section: the list, the boot guard, the
two Gradle tasks, the optionality rules and the four generation failures.
frontend-components' "closed mirror" paragraph now describes a build step.

Refs #743

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

## Deploy and live verification

Run after the branch merges, per the spec's Verification section. Not a task — no code changes.

1. `make test` green (the full local gate, including `:backend:checkApiTypes`) and `make qa`
   green against a running stack.
2. Staleness, demonstrated end to end: add a field to any DTO under `model/api/`, run
   `./gradlew :backend:checkApiTypes`, watch it fail naming the file, revert.
3. Live on a `pg_dump` copy:
   - `V63` applies; `availability_watch.done_reason` exists with its CHECK.
   - `GET /api/watches` on a watch seeded `done` by trigger carries `done_reason: "triggered"`;
     one seeded `done` by the reaper carries `"elapsed"`; a row retired before the migration
     omits the key entirely (`explicitNulls = false`).
   - `GET /api/pois/{id}/campsites` still serves `booking_provider`.
   - `/api/docs` still boots and still lists the documented paths.
4. The generated file, reviewed against the deleted mirrors by hand: every removed union is
   present as a generated `export type`, and every removed field is present on the generated
   interface. `git show` the Task 5 and Task 6 diffs side by side with
   `frontend/src/api/generated/api-types.ts`.

## Out of scope (backlog, note on #743)

- Feeding `components/schemas` in `/api/docs/openapi.json` from `ApiContract`. The list is
  shaped for it; the JSON-Schema emitter is a second generator over the same descriptor walk.
- Runtime response validation in `http.ts`.
- Adopting or deleting `POST /api/pois/availability/bulk`. It has no client anywhere in the
  repo; its types are now generated and unused, which is at least honest.
- A global `JsonNamingStrategy.SnakeCase` in place of the 222 `@SerialName` annotations.
  Wire-visible, so it is its own change with its own verification.
- The `*Dto` vs `*Schema` naming split in `model/api/`. The generator uses whatever the class
  is called, so a rename is a generated-file diff and nothing else.
- Typing the fetch helpers against the emitted `API_ENDPOINTS`, which this phase emits and
  nothing consumes.
- The `XDtoInput` request variant. The generator fails by name when a shared class's two
  optionality rules disagree; whoever hits that first designs the variant, including the
  rename of every type that references it.
