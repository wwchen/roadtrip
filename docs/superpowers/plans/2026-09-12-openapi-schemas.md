# OpenAPI Schemas From the Contract, and a Body Check Behind Every Route Test — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One structured `WireType` sits between the serialization descriptor and both renderers, so `frontend/src/api/generated/api-types.ts` and `/api/docs/openapi.json`'s `components/schemas` are two views of the same walk with the same optionality and the same refusals; `ApiContract` carries a status on every body; and every JSON response any route test produces is strict-decoded and re-encoded against the contract row for its method, path and status, so a row naming the wrong DTO, a DTO that drifted from what the route serves, or an undeclared status fails the build.

**Architecture:** `apigen/DescriptorWalk.kt` stops returning TypeScript text and returns `WireType`; `apigen/TsRender.kt` renders `WireType` to the same text as today (proven byte-identical by `checkApiTypes`) and `apigen/JsonSchemaRender.kt` renders it to `io.ktor.openapi.JsonSchema`. `ApiEndpoint.response: KClass<*>?` becomes `success: ApiBody` and `errors: List<KClass<*>>` becomes `List<ApiBody>`, where `ApiBody(status, body?)`. `apigen/OpenApiContractDocument.apply` takes the document Ktor builds from the routing tree, writes `components.schemas` from one cached walk of the whole contract, and fills each mounted operation's `requestBody` and `responses` from its row plus the 401/403 its route's `RouteAccess` implies. A test-side Ktor plugin installed by `routeTestApplication` intercepts the send pipeline at `Render`, finds the contract row for the matched route, strict-decodes the JSON body into the class the row names for that status, re-encodes it with `roadtripApiJson`, compares the two `JsonElement` trees, and appends the exercised `(method, path, status)` to a ledger file that a `:backend:contractLedgerCheck` Gradle task reads after `:backend:test`.

**Tech Stack:** Kotlin 2.4.10 / Ktor 3.5.2 (`io.ktor:ktor-openapi-schema`, `io.ktor:ktor-server-routing-openapi`) / kotlinx-serialization-json 1.11.0 / Gradle (`JavaExec`) / jOOQ + Postgres (Flyway, Testcontainers via `SharedDbTest`) / JUnit 5 + kotlin.test / TypeScript 7 + vitest 4.

**Spec:** `docs/superpowers/specs/2026-09-12-openapi-schemas-design.md`. Issue #754. Follows `docs/superpowers/plans/2026-09-12-api-contract.md` (#743, merged in #752), base `3c026c01` plus `3aa375c1` (the spec commit).

## Resolutions

Where the spec left a choice, or its letter fought what is actually in the tree, this plan decided. Each decision is load-bearing for the task that implements it. The Ktor model facts below were verified against the published sources of `io.ktor:ktor-openapi-schema-jvm:3.5.2` and `io.ktor:ktor-server-routing-openapi-jvm:3.5.2`, not inferred.

1. **`io.ktor.openapi.JsonSchema` has no `propertyNames`, so an enum-keyed map does not get one.** The spec's mapping table says `MapOf(k, v)` with an `EnumRef` key "adds `propertyNames: {$ref}`". `JsonSchema` is a 39-property data class and `propertyNames` is not among them (`type`, `title`, `description`, `required`, `allOf`, `oneOf`, `not`, `anyOf`, `properties`, `additionalProperties`, `discriminator`, `readOnly`, `writeOnly`, `xml`, `externalDocs`, `example`, `examples`, `deprecated`, `maxProperties`, `minProperties`, `default`, `format`, `items`, `prefixItems`, `maximum`, `exclusiveMaximum`, `minimum`, `exclusiveMinimum`, `maxLength`, `minLength`, `pattern`, `maxItems`, `minItems`, `uniqueItems`, `enum`, `multipleOf`, `$id`, `$anchor`, `$dynamicAnchor`). An enum-keyed map therefore renders `{type: object, additionalProperties: <value>}` exactly like a string-keyed one, the key union is still declared as its own component because the walk reaches it, and the constraint the document cannot express is stated in `docs/backend-architecture.md`. Task 3.
2. **A map key is only ever `Str` or `EnumRef`, never `Num`.** `DescriptorWalk.mapKeyType` renders *every* `PrimitiveKind` key — `Int`, `Long`, `Double` included — as `string` today, because a JSON object key is a string. The `WireType` declaration keeps `Num(integer)` (values need it) but `MapOf.key` is populated with `Str` for any primitive kind and `EnumRef` for an enum, so the TypeScript stays byte-identical and the JSON Schema never claims a numeric property name. Task 1.
3. **`POST /auth/password/complete` answers `204`, not "200 with no body".** The spec's §3 bullet says 200; `AuthRoutes.kt:178` is `call.respond(HttpStatusCode.NoContent)`, and the api-contract plan's own row table already says 204. The row is `success = ApiBody(HTTP_NO_CONTENT)`. Task 2.
4. **`GET /api/health/ready` serves its success DTO at `503` as well as `200`.** `HealthRoutes.kt:37-42` responds `readinessResponseDto(...)` with `if (report.isReady) OK else ServiceUnavailable`. The spec's status inventory does not mention it; without the row entry the body check fails the moment `HealthRoutesTest` moves onto the harness. The row lists `ApiBody(HTTP_SERVICE_UNAVAILABLE, ReadinessResponseDto::class)`. Task 2.
5. **`GET /api/pois/{id}/campsites` serves `ApiErrorSchema` at `404` too.** `CampsiteRoutes.kt:68` answers `AvailabilityServiceError.NotFound` with `respondApiError(e.error, NotFound)`. The spec's inventory names only the `/availability` sibling. Task 2.
6. **A row may declare `401`/`403`; what it must never do is restate the access guard's.** The spec says "401 and 403 are not on the rows: the document builder adds them from the route's access level". That holds only for `RouteAccess.User` and `RouteAccess.HasRole`, the two levels `RouteAccessDsl.canDeny()` enforces. Three route families answer 401 or 403 from their own handlers on routes whose level supplies neither: `POST /auth/password/complete` is `Anonymous` and answers `401 login_failed` (`AuthRoutes.kt:170`); the three `UserOrCapability` watch rows answer 401 and 403 through `respondFailure` (`AvailabilityWatchRoutes.kt:158,160`); and `POST /api/booking/add-to-cart` is `User` — which yields only 401 — yet answers `403` for `CREDENTIALS_REQUIRED` and `CALLER_ACTION` (`BookingRoutes.kt:120,127`), as does `POST /api/watches`. Those statuses go on the rows, the document builder merges its access-derived entries by `(status, class)` so nothing is duplicated, and `ApiContractCoverageTest` pins the checkable half of the rule: every `401`/`403` entry on any row names `ApiErrorSchema`, the only class any route serves at those statuses. Tasks 2 and 4.
7. **A row with no mounted operation is skipped, not a build failure.** The spec wants an absent operation to fail the document build. Every test that boots `/api/docs` mounts a *slice* of the tree — `OpenApiSmokeTest` mounts six route functions out of twenty — so a throw would make the document unbuildable in exactly the place it is tested. The boot guard in `registerKoinRoutes` is already the authority on completeness: it fails the boot when a non-`conditional` row is unmounted, so skipping loses nothing in production and makes the builder work against any subset. `components.schemas` is still rendered from the *whole* contract, so every `$ref` in a partial document resolves. Task 4.
8. **The spec's `POST /api/watches` / force-poller / delete-watch document assertions move to a unit test.** They cannot live in `OpenApiSmokeTest` for the reason above without dragging `AvailabilityWatchController` and its six dependencies into a smoke test. `OpenApiContractDocumentTest` builds a synthetic `OpenApiDoc` with the two `PathItem`s it needs and asserts 201 + `requestBody` + 401, 429 → `CheckNowCooldownDto`, and 204 with no content. `OpenApiSmokeTest` keeps the whole-document invariants its own slice supports, plus `GET /api/health/ready` (200 and 503), `GET /api/pois/{id}` (200, 400, 404) and `POST /api/pois` (`requestBody`), and gains `availabilityDashboardRoutes` over a detached `DSLContext` — the pattern it already uses for `RouteCorridorRepo` — so the 429 row is in the live document too. The spec's "`components.schemas`' key set equals the set of names the TypeScript generator declares" lands in `JsonSchemaRenderTest` (`the real contract renders a schema for every name the TypeScript declares`), which compares the schema keys against `walkContract()`'s own interfaces and enums — the set the TypeScript file is rendered from — while the smoke test compares the *served* document against `contractSchemas()`. Tasks 3 and 4.
9. **`/auth/password/**` joins the document.** `ApiDocsRoutes.includeInRoadtripOpenApi` accepts `/api/**` (minus the Swagger subtree) and `/test/**` only, so the two password rows have no `paths` entry at all. The predicate becomes `isContractedPath(path) || path.startsWith(TEST_PATH_PREFIX)`, reusing the one spelling `RouteInventory.kt` already owns — which is `/api/**` plus `/auth/password/**` minus `/api/docs/**`, i.e. today's behaviour plus the two rows the contract already declares. Task 4.
10. **`/test/**` operations are left exactly as the routing tree describes them.** They are in the document (they were before this change) and no row names them, so the builder never touches them: no `requestBody`, no `responses`, no schemas. Same for `/api/docs/openapi.json`, which is `.hide()`n and excluded by the predicate.
11. **The ledger is a TSV the plugin appends to and a `JavaExec` task reads.** `:backend:test` runs `useJUnitPlatform()` with the default `maxParallelForks` (one JVM) and `junit-platform.properties` set to `classes.default=concurrent`, so test *classes* run as threads in one process. One file with a lock is therefore sufficient and no per-fork naming is needed. Gradle passes its absolute path as the `roadtrip.contractLedger` system property and deletes it in `tasks.test`'s `doFirst`; the plugin appends one line per checked response; `:backend:contractLedgerCheck` is a `JavaExec` over `sourceSets["test"].runtimeClasspath` running `ContractLedgerCheckKt.main`, `mustRunAfter(tasks.test)`, and `tasks.test { finalizedBy(...) }` so `make test`'s bare `:backend:test` still gates. A `JavaExec` rather than a second `Test` task deliberately: a second `Test` task would join Kover's instrumented set and change what `koverVerify` measures, and `GenerateApiTypes` is the precedent for an argv-and-exit verifier already in this repo. With no system property (an IDE run) the plugin still checks bodies and simply writes nothing. Task 6.
12. **The plugin hooks `ApplicationSendPipeline.Render`, through a custom `Hook`.** The phase order is `Before, Transform, Render, ContentEncoding, TransferEncoding, After, Engine`. `onCallRespond` is `Transform`, where the subject may still be the un-serialized DTO that `ContentNegotiation` is about to convert; the built-in `ResponseBodyReadyForSend` hook is `After`, by which point `Compression` may have replaced the body. `Render` is after `ContentNegotiation` and before `Compression`, so the subject is the final `TextContent` in both response paths: `respondText` (which `respondEncodedJson` uses) produces one directly, and `KotlinxSerializationConverter.serializeContent` returns `TextContent` for a `StringFormat` — the only `ChannelWriterContent` path in `ktor-serialization-kotlinx-json` is the `Flow`/`Sequence` extension, which no route uses. The call reaching that interceptor is the `RoutingPipelineCall` (`RoutingRoot.executeResult` merges the application's send pipeline with the route's and executes it with the routing call), so `call.route` gives the matched node and `path(OpenApiRoutePathFormat)` its template. Task 5.
13. **The strict decoder is `Json { ignoreUnknownKeys = false; explicitNulls = false }`.** The spec says `Json { ignoreUnknownKeys = false }`. Left at the default `explicitNulls = true`, a strict decode rejects a body that omits a *nullable field with no default* — which the one encoder always omits — so the check would fail on honest bodies. The decoder mirrors `roadtripApiJson` and tightens exactly one setting: an unknown key. Task 5.
14. **The contract row map is the ignore list.** The spec names `/test/**` and `/data/**` as ignored prefixes. A response whose `(method, path)` matches no row is skipped, which covers `/test/**`, `/data/**`, `/api/docs/openapi.json` and the synthetic probe paths the coverage tests mount, with no second list to keep in sync with `ApiContract`. Task 5.
15. **Eight route tests call bare `routing { }`, not three.** The spec names `AvailabilityDashboardRoutesTest`, `AdminIngestRoutesTest` and `SlackInteractivityRoutesTest`. `BookingRoutesTest`, `BuildInfoRoutesTest`, `AuthRoutesTest`, `HealthRoutesTest` and `ReadinessPoolExhaustionTest` do too, and the first four own the only 2xx coverage of `/api/booking/add-to-cart`, `/api/build-info`, `/api/me`, `/api/health` and `/api/health/ready`, so the ledger cannot go green without them. All seven switch. `ReadinessPoolExhaustionTest` stays on bare `routing { }`: it measures what a genuinely exhausted Hikari pool does to the readiness probe, `HealthRoutesTest` already produces both of that row's bodies through the harness, and wrapping a pool-exhaustion test in `Compression` and `CachingHeaders` changes what it measures for no coverage gain. Task 5.
16. **Two bodies at one status render `oneOf`; no row in this contract has any.** Every status in the 46-row table carries exactly one class. `JsonSchemaRender` and the document builder implement `oneOf` anyway — the shape is one line and the alternative is a build failure the first time a route grows a second refusal body — and `OpenApiContractDocumentTest` exercises it on a synthetic row. Task 4.
17. **`describeApi` for the three bare route files comes from each file's own KDoc.** `RouteRoutes.kt:20-30`, `GeocodeRoutes.kt:18-31` and `SlackInteractivityRoutes.kt`'s header already say what each route is, in prose written when it was built. The tag and summary are lifted from there rather than invented, and the KDoc that becomes a duplicate of the summary is trimmed to what the summary does not say. Task 4.
18. **Every `/api/settings/**` row lists the shared mapper's whole status set: 400, 409, 502, 503.** `SettingsErrorResponses.respondSettingsError` is one function over one sealed `SettingsError`, installed as a `StatusPages` exception handler, so any settings handler can reach any of its four statuses; splitting the set per row would be a claim about which `SettingsError` each service can raise, which nothing checks. A row that declares a status the route cannot reach costs only a published response the client never sees; a row that omits one it can reach fails the body check. Task 2.
19. **`ApiStatus.kt` holds the statuses as top-level `HTTP_*` constants.** `model/` may not name Ktor (`LayeringGuardTest`), so `HttpStatusCode.Created.value` is unavailable where the rows are written, and AGENTS.md forbids `201` at a call site. Top-level `const val`s in `ca.floo.roadtrip.model.api` need no import inside `ApiContract.kt`; the `HTTP_` prefix keeps them out of the way of everything else in that package. Same reason `ApiMethod` exists. Task 2.
20. **`ApiEndpoint.errors` keeps its `[ApiBody(400, ApiErrorSchema)]` default, and `apiErrors(vararg statuses)` builds the common list.** Thirty-one of the 46 rows serve nothing but `ApiErrorSchema`, at between one and four statuses; `errors = apiErrors(HTTP_BAD_REQUEST, HTTP_CONFLICT, HTTP_BAD_GATEWAY, HTTP_SERVICE_UNAVAILABLE)` is the readable form, and a row mixing classes writes the list out. `success` gets no helper: the whole point of this phase is that every row states its status, so every row spells it. Task 2.
21. **`walkContract(endpoints)` is the one walk; both renderers and the document read its result.** `generateApiTypes` currently does the walk inline and sorts the rows before rendering. The walk moves into `walkContract`, which returns `interfaces`, `enums` and `rows` **in declaration order**; sorting moves into the TypeScript renderer. The document builder then zips `ApiContract.endpoints` with `walk.rows` positionally and takes the schema names the walk already claimed, instead of re-deriving `tsName(serialName)` and risking a name the collision map never approved. Tasks 1, 3 and 4.

## Global Constraints

These are the spec's binding rules. Every task's requirements implicitly include this section.

- **The `WireType` shape**, verbatim from the spec (`MapOf.key` is populated per Resolution 2):

  ```kotlin
  internal sealed interface WireType {
      data object Str : WireType
      data class Num(val integer: Boolean) : WireType       // Int/Long/Short/Byte → integer; Float/Double → number
      data object Bool : WireType
      data object Any : WireType                             // JsonElement
      data object AnyObject : WireType                       // JsonObject
      data object AnyArray : WireType                        // JsonArray
      data object Primitive : WireType                       // JsonPrimitive: string | number | boolean
      data class Ref(val name: String) : WireType            // a declared interface
      data class EnumRef(val name: String) : WireType        // a declared enum
      data class ArrayOf(val element: WireType) : WireType
      data class MapOf(val key: WireType, val value: WireType) : WireType   // key is Str or EnumRef
  }
  ```

- **`DescriptorWalk.typeOf` returns `WireType`; `TsField.type` becomes `WireType`; every refusal stays exactly where it is** — the eight named failures at `DescriptorWalk.kt` ~:100-108, :124-130, :150-154, :213-226, :236-240 and `ApiTypeGenerator.kt` ~:67-73, :95-99 keep their messages and their trigger conditions.
- **The TypeScript output is byte-identical through the refactor**, proven by `:backend:checkApiTypes` passing with the committed file untouched. `integer` and `number` both render `number`. `ApiTypeGeneratorTest` asserts rendered text and passes unchanged through Task 1.
- **The JSON-Schema mapping table**, exactly (the `propertyNames` row of the spec's table is dropped per Resolution 1):

  | WireType | JSON Schema |
  |---|---|
  | `Str` | `{type: string}` |
  | `Num(integer=true)` | `{type: integer}` |
  | `Num(integer=false)` | `{type: number}` |
  | `Bool` | `{type: boolean}` |
  | `Any` | `{}` |
  | `AnyObject` | `{type: object}` |
  | `AnyArray` | `{type: array}` |
  | `Primitive` | `{type: [string, number, boolean]}` |
  | `Ref(n)` / `EnumRef(n)` | `{$ref: "#/components/schemas/n"}` |
  | `ArrayOf(e)` | `{type: array, items: e}` |
  | `MapOf(k, v)` | `{type: object, additionalProperties: v}` |
  | interface | `{type: object, title: n, properties: {...}, required: [non-optional fields], additionalProperties: false}` |
  | enum | `{type: string, title: n, enum: [serial names]}` |

- **`required` is the complement of the walk's optionality, under the same rule as TypeScript:** for a response class a field is required iff it is non-nullable; for a request class, iff it is non-nullable and has no default. A class reachable from both roots must agree or generation fails — already the rule, and already `ApiTypeGenerator.merge`'s job.
- **`additionalProperties: false` on every interface.** Accurate for responses, because the encoder emits only declared keys; the contract's statement for requests even though the decoder tolerates extras, and `docs/backend-architecture.md` says so.
- **No schema carries a `null` type**, because the encoder never writes one. The `JsonNull`-inside-`Json*` caveat from #752 applies to `Any`, `AnyObject`, `AnyArray` and `Primitive` only and is documented on those rows.
- **Schema names equal the claimed TypeScript names**, so `components/schemas/CampsiteDto` and `interface CampsiteDto` are one declaration rendered twice. The names come from the walk (Resolution 21), never re-derived.
- **The contract shape:**

  ```kotlin
  data class ApiBody(val status: Int, val body: KClass<*>? = null)   // body null only for 204 and empty-text answers

  data class ApiEndpoint(
      val method: ApiMethod,
      val path: String,
      val request: KClass<*>? = null,
      val success: ApiBody,
      val errors: List<ApiBody> = listOf(ApiBody(HTTP_BAD_REQUEST, ApiErrorSchema::class)),
      val conditional: Boolean = false,
  )
  ```

- **`success` is the 2xx the route answers with:** 200 by default, 201 for `POST /api/watches`, 204 with no body for `POST /api/watches/{id}/delete` and `POST /auth/password/complete`, 200 with no body for `POST /api/slack/interactivity`.
- **`errors` lists every non-2xx body the route itself serializes, one entry per `(status, class)`.** `400 ApiErrorSchema` is the universal `StatusPages` default (`ContentTransformationException`, `BadRequestException`, `MissingRequestParameterException`, `ParameterConversionException`). 401 and 403 appear only where the handler writes one itself (Resolution 6).
- **`API_ENDPOINTS`' new row shape:** `{ method, path, request, success: { status, type }, errors: [{ status, type }] }`, `errors` sorted by status then type, rows still sorted by path then method, still `as const`. `frontend/src/api/api-endpoints.test.ts` reads `path` only and keeps working; a companion assertion there checks every `success.type` and `errors[].type` names a declared interface or union.
- **`ApiContractCoverageTest` keeps `CONTRACT_ROW_COUNT = 46`** and gains: every `ApiBody.status` is in `200..599`; `success.status` is in `200..299` and no `errors` entry is; no row declares the same `(status, class)` twice; a 204 carries no body; every 401/403 entry names `ApiErrorSchema`.
- **The body-check plugin's four steps**, on every response whose content type is `application/json` and whose matched route matches a contract row by method and path template:
  1. pick the class for the response status (`success` or the matching `errors` entry; a status with no entry fails the test naming method, path and status);
  2. strict-decode the body with the class's serializer (Resolution 13);
  3. re-encode with `roadtripApiJson` and assert the two `JsonElement` trees are equal;
  4. record the `(method, path, status)` as exercised in the ledger.
  `Json*` fields are compared as trees like everything else, so a `JsonNull` inside one round-trips honestly. **Never loosen the check to make a revealed drift pass** — fix the DTO or fix the row, and report the drift.
- **Ignored paths:** a response whose `(method, path)` names no row is skipped (Resolution 14), which is what covers `/test/**` and `/data/**`.
- **Six rows gain a 2xx route test:** `PUT /api/settings/notifications`, `GET /api/pois/{id}`, `GET /api/availability/pollers/{id}/runs`, `GET /api/availability/changes`, `GET /api/admin/data/runs/{id}`, and `POST /auth/password/begin` (a route test with a stubbed wiring).
- **The exercised ledger:** the suite fails when a row with a success body is never produced by any test.
- **`OpenApiSmokeTest` gains:** `components.schemas` is non-empty and its key set equals the set of names the TypeScript generator declares; every operation under `/api/**` whose row exists has `responses`; every `$ref` in the document resolves.
- **Docs.** `docs/backend-architecture.md`: the "not caught" sentence is replaced by what is now caught and how; a short `/api/docs` subsection (what the tree contributes, what the contract contributes, how statuses and 401/403 get there); the loop becomes `add the route → add the row with its statuses → make api-types → commit the diff`; the request-`additionalProperties` note. `AGENTS.md`'s generated-types bullet gains "and `/api/docs` schemas".
- **Layering rules, verbatim from `AGENTS.md`:**
  - Prefer typed Kotlin/Java DTOs (`@Serializable` data classes or existing schema classes) for request/response bodies. Do not hand-build JSON strings in routes when a DTO can represent the shape.
  - Services take a `UnitOfWork` (when several writes must land together) or the repo handles they use — never a `DSLContext`. `org.jooq` appears only under `repo/`, `db/`, and `di/InfraModule.kt`; `LayeringGuardTest` fails the build otherwise.
  - SQL, jOOQ DSL queries, table references, and persistence mapping belong in `repo` classes only.
  - Layering is `routes -> service -> repo`: routes are the HTTP shell and do not add new route-to-repo paths.
  - Keep business logic out of routes; put orchestration in `service` and persistence in `repo`.
  - **No inline magic constants.** Every literal introduced here is a named `const val`: the HTTP statuses (`ApiStatus.kt`), the TypeScript primitive names, the `$ref` prefix Ktor's own `ReferenceOr.schema` owns, the ledger property name and its line kinds, the JSON content type.
  - **Layered abstractions.** `model/` imports neither Ktor nor jOOQ — `ApiBody` is plain Kotlin and `ApiStatus` exists so the rows need no `HttpStatusCode`. `apigen` may name Ktor (it is neither `model/` nor `service/`) but never `route/`: the access level reaches `OpenApiContractDocument.apply` as an `accessOf` lambda the route layer supplies.
  - **Reusable components.** `RouteAccessCoverage.hasReachableAccess` and the document builder's access lookup are one walk, in `RouteInventory.kt`. The `PathItem` verb dispatch is a registry, not two parallel `when` blocks.
  - **No half-finished implementations.** If a method exists, it works.
- **Comments short and rare.** Keep KDoc whose claim stays true; delete the paragraphs this change falsifies (`ApiContract`'s "`[response]` is the 2xx body" paragraph, `backend-architecture.md`'s "is not caught" sentence, `RouteRoutes`/`GeocodeRoutes` KDoc that the new summary now says). Do not narrate the refactor in comments. KDoc must not contain a `/**` or a `route/**`-style token — that opens a nested comment; write `route/` or backtick the glob.
- **detekt/ktlint:** the active detekt rules are exactly `config/detekt/detekt.yml` (`buildUponDefaultConfig = false`) — naming, `ClassOrdering`, `DataClassContainsFunctions` (only a `to`/`as` prefix allowed, so an `ApiEndpoint` member function is out; use a top-level extension), `DataClassShouldBeImmutable`, `MatchingDeclarationName`. A non-`const` private top-level `val` needs `@Suppress("TopLevelPropertyNaming")` (precedent: `PoiRegistry.kt`, `DescriptorWalk.kt`); `const val` uses `SCREAMING_SNAKE`; lines stay under 140 characters; ktlint forbids wildcard imports and wraps chained calls.
- **Backend gate, run at the end of every backend task:** `./gradlew :backend:test :backend:koverXmlReport :backend:koverVerify :backend:ktlintCheck :backend:detekt :backend:checkApiTypes --offline -q` from the worktree root. Docker must be running (Testcontainers for `SharedDbTest`, and `generateJooq` for the compile).
- **Frontend gate, run at the end of every task that touches `frontend/`:** `cd frontend && npm run typecheck && npm run test && npm run lint`.
- One commit per task, conventional prefix, `Refs #754`, trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- A hook denies the first Write/Edit to each file per session: state the facts it asks for in one line and re-issue the identical call. Never run bare `git stash`.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `backend/src/main/kotlin/ca/floo/roadtrip/apigen/WireType.kt` (new) | The one structured type between the descriptor and both renderers | 1 |
| `backend/src/main/kotlin/ca/floo/roadtrip/apigen/TsRender.kt` (new) | `WireType` → the same TypeScript text as today | 1 |
| `backend/src/main/kotlin/ca/floo/roadtrip/apigen/DescriptorWalk.kt` | `typeOf` returns `WireType`; every refusal unchanged | 1 |
| `backend/src/main/kotlin/ca/floo/roadtrip/apigen/TsTypes.kt` | `TsField.type: WireType`; `TsBody`; `TsEndpoint`'s new shape | 1, 2 |
| `backend/src/main/kotlin/ca/floo/roadtrip/apigen/ApiTypeGenerator.kt` | `walkContract` (one walk, declaration order) + the TS renderer | 1, 2 |
| `backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiStatus.kt` (new) | The `HTTP_*` statuses, so `model/` need not name Ktor | 2 |
| `backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiContract.kt` | `ApiBody`, `success`, `errors`, `apiErrors`, `bodyAt`, and the 46 rows with their statuses | 2 |
| `frontend/src/api/generated/api-types.ts` (generated) | The new `API_ENDPOINTS` row shape | 2 |
| `frontend/src/api/api-endpoints.test.ts` | The companion assertion over `success.type` / `errors[].type` | 2 |
| `backend/src/main/kotlin/ca/floo/roadtrip/apigen/JsonSchemaRender.kt` (new) | `WireType` + the walk → `io.ktor.openapi.JsonSchema` | 3 |
| `backend/src/main/kotlin/ca/floo/roadtrip/apigen/OpenApiContractDocument.kt` (new) | Schemas, request bodies, responses and access-derived 401/403 onto the document | 4 |
| `backend/src/main/kotlin/ca/floo/roadtrip/route/common/RouteInventory.kt` | `declaredAccessByLeaf` / `reachableAccess`, the one access walk | 4 |
| `backend/src/main/kotlin/ca/floo/roadtrip/route/common/RouteAccessCoverage.kt` | Delegates to `reachableAccess` | 4 |
| `backend/src/main/kotlin/ca/floo/roadtrip/route/api/docs/ApiDocsRoutes.kt` | The predicate widening and the contract hand-off | 4 |
| `backend/src/main/kotlin/ca/floo/roadtrip/route/api/route/RouteRoutes.kt`, `.../geocode/GeocodeRoutes.kt`, `.../slack/SlackInteractivityRoutes.kt` | A tag and summary each | 4 |
| `backend/src/test/kotlin/ca/floo/roadtrip/route/ContractBodyCheck.kt` (new) | The test-side plugin: decode, re-encode, compare, record | 5 |
| `backend/src/test/kotlin/ca/floo/roadtrip/route/RouteTestApplication.kt` | Installs the plugin | 5 |
| `backend/src/test/kotlin/ca/floo/roadtrip/route/ContractLedgerCheck.kt` (new) | `main()`: every row with a success body was produced | 6 |
| `backend/build.gradle.kts` | The ledger property, the `doFirst` delete, `contractLedgerCheck` | 6 |
| `Makefile`, `.github/workflows/ci.yml` | `:backend:contractLedgerCheck` in both gates | 6 |
| `docs/backend-architecture.md`, `AGENTS.md` | What is now caught, the `/api/docs` subsection, the loop | 7 |

---
### Task 1: `WireType` and `TsRender` — one structured type, byte-identical TypeScript

The descriptor walk stops producing text. `typeOf` returns a `WireType`, `TsField.type`
becomes a `WireType`, and a new renderer turns `WireType` back into exactly the strings
`DescriptorWalk` used to return. Nothing about the generated file changes, which is the whole
point: `:backend:checkApiTypes` passes against the committed file, untouched.

The walk also gains a name. `walkContract(endpoints)` returns the interfaces, the enums and
the endpoint rows **in declaration order**, and `generateApiTypes` becomes the renderer over
that result. Tasks 3 and 4 read the same `walkContract`, so the schemas and the document can
never disagree with the TypeScript about a name.

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/apigen/WireType.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/apigen/TsRender.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/apigen/DescriptorWalk.kt:11-19,81-110,117-132,141-156,229-242`, `backend/src/main/kotlin/ca/floo/roadtrip/apigen/TsTypes.kt:3-8`, `backend/src/main/kotlin/ca/floo/roadtrip/apigen/ApiTypeGenerator.kt:32-61,119-126`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/apigen/WireTypeTest.kt` (new); `backend/src/test/kotlin/ca/floo/roadtrip/apigen/ApiTypeGeneratorTest.kt` (unchanged — it is the proof)

**Interfaces:**
- Consumes: `kotlinx.serialization.descriptors.SerialDescriptor` (`kind`, `isInline`, `isNullable`, `serialName`, `elementsCount`, `getElementName`, `getElementDescriptor`, `isElementOptional`, `nonNullOriginal`); `ca.floo.roadtrip.model.api.ApiContract`, `ApiEndpoint`, `ApiMethod` (all unchanged in this task).
- Produces:
  - `ca.floo.roadtrip.apigen.WireType` — the sealed interface quoted in Global Constraints: `Str`, `Num(integer: Boolean)`, `Bool`, `Any`, `AnyObject`, `AnyArray`, `Primitive`, `Ref(name: String)`, `EnumRef(name: String)`, `ArrayOf(element: WireType)`, `MapOf(key: WireType, value: WireType)`
  - `ca.floo.roadtrip.apigen.tsTypeOf` — `internal fun tsTypeOf(type: WireType): String`
  - `ca.floo.roadtrip.apigen.DescriptorWalk.typeOf` — `fun typeOf(descriptor: SerialDescriptor, within: String = descriptor.serialName): WireType`
  - `ca.floo.roadtrip.apigen.TsField` — `internal data class TsField(val name: String, val type: WireType, val optional: Boolean)`
  - `ca.floo.roadtrip.apigen.ContractWalk` — `internal data class ContractWalk(val interfaces: List<TsInterface>, val enums: List<TsEnum>, val rows: List<TsEndpoint>)`
  - `ca.floo.roadtrip.apigen.walkContract` — `internal fun walkContract(endpoints: List<ApiEndpoint> = ApiContract.endpoints): ContractWalk`
  - `ca.floo.roadtrip.apigen.generateApiTypes` — `internal fun generateApiTypes(endpoints: List<ApiEndpoint> = ApiContract.endpoints): String` (signature unchanged)

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/apigen/WireTypeTest.kt`. The fixture DTOs it
names (`FixtureShapes`, `FixtureScalars`, `FixtureEnumKeyMap`) are the top-level fixtures
`ApiTypeGeneratorTest.kt` already declares in this same package:

```kotlin
package ca.floo.roadtrip.apigen

import ca.floo.roadtrip.model.api.ApiContract
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.full.createType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The walk's structured output, and the TypeScript renderer over it.
 *
 * The mapping table itself is asserted as *text* by [ApiTypeGeneratorTest]; this
 * fixes the shape the schema renderer reads, so a later change that keeps the
 * text identical while flattening the structure fails here rather than silently
 * costing `/api/docs` its `integer` types.
 */
class WireTypeTest {
    @Test
    fun `the walk reports structure, not text`() {
        val walk = DescriptorWalk(Optionality.RESPONSE, HashMap())
        walk.typeOf(descriptorOf(FixtureShapes::class))
        val shapes = walk.interfaces.getValue(FixtureShapes::class.qualifiedName!!)
        assertEquals(
            listOf(
                TsField("names", WireType.ArrayOf(WireType.Str), optional = false),
                TsField("tags", WireType.ArrayOf(WireType.Str), optional = false),
                TsField("by_id", WireType.MapOf(WireType.Str, WireType.Ref("FixtureScalars")), optional = false),
                TsField("flavour", WireType.EnumRef("FixtureFlavour"), optional = false),
                TsField("blob", WireType.Any, optional = false),
                TsField("bag", WireType.AnyObject, optional = false),
                TsField("rows", WireType.AnyArray, optional = false),
                TsField("scalar", WireType.Primitive, optional = false),
                TsField("id", WireType.Num(integer = true), optional = false),
            ),
            shapes.fields,
        )
    }

    @Test
    fun `an integer and a fractional number are distinct in the walk and identical in TypeScript`() {
        val walk = DescriptorWalk(Optionality.RESPONSE, HashMap())
        walk.typeOf(descriptorOf(FixtureScalars::class))
        val scalars = walk.interfaces.getValue(FixtureScalars::class.qualifiedName!!)
        assertEquals(
            listOf(
                WireType.Str,
                WireType.Str,
                WireType.Num(integer = true),
                WireType.Num(integer = true),
                WireType.Num(integer = false),
                WireType.Bool,
            ),
            scalars.fields.map { it.type },
        )
        assertEquals("number", tsTypeOf(WireType.Num(integer = true)))
        assertEquals("number", tsTypeOf(WireType.Num(integer = false)))
    }

    @Test
    fun `an enum-keyed map keeps the enum in the key position`() {
        val walk = DescriptorWalk(Optionality.RESPONSE, HashMap())
        walk.typeOf(descriptorOf(FixtureEnumKeyMap::class))
        val map = walk.interfaces.getValue(FixtureEnumKeyMap::class.qualifiedName!!).fields.single().type
        assertEquals(WireType.MapOf(WireType.EnumRef("FixtureFlavour"), WireType.Str), map)
        assertEquals("Record<FixtureFlavour, string>", tsTypeOf(map))
    }

    @Test
    fun `every mapping-table arm renders the text the generated file already carries`() {
        assertEquals("string", tsTypeOf(WireType.Str))
        assertEquals("boolean", tsTypeOf(WireType.Bool))
        assertEquals("unknown", tsTypeOf(WireType.Any))
        assertEquals("Record<string, unknown>", tsTypeOf(WireType.AnyObject))
        assertEquals("unknown[]", tsTypeOf(WireType.AnyArray))
        assertEquals("string | number | boolean", tsTypeOf(WireType.Primitive))
        assertEquals("CampsiteDto", tsTypeOf(WireType.Ref("CampsiteDto")))
        assertEquals("WatchStatus", tsTypeOf(WireType.EnumRef("WatchStatus")))
        assertEquals("CampsiteDto[]", tsTypeOf(WireType.ArrayOf(WireType.Ref("CampsiteDto"))))
        assertEquals(
            "Record<string, CampsiteDto[]>",
            tsTypeOf(WireType.MapOf(WireType.Str, WireType.ArrayOf(WireType.Ref("CampsiteDto")))),
        )
    }

    @Test
    fun `the contract walk hands back rows in declaration order`() {
        assertEquals(
            ApiContract.endpoints.map { it.path },
            walkContract().rows.map { it.path },
            "walkContract must not sort: the document builder zips its rows against ApiContract.endpoints",
        )
    }

    private fun descriptorOf(kClass: KClass<*>) = serializer(kClass.createType()).descriptor
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.apigen.WireTypeTest' --offline -q`
Expected: FAIL to compile — `Unresolved reference: WireType`, `tsTypeOf`, `walkContract`, and
a `TsField` argument type mismatch (`String` where `WireType` is expected).

- [ ] **Step 3: Add `WireType`**

Create `backend/src/main/kotlin/ca/floo/roadtrip/apigen/WireType.kt`:

```kotlin
package ca.floo.roadtrip.apigen

/**
 * What one wire position is, structurally.
 *
 * The one thing between [DescriptorWalk] and its two renderers: [tsTypeOf] turns
 * it into TypeScript and `JsonSchemaRender` into a JSON Schema, so the generated
 * `.ts` file and the `components/schemas` of `/api/docs` are two views of a
 * single walk with one optionality rule and one set of refusals.
 *
 * [Num.integer] is the one distinction TypeScript cannot carry: both arms render
 * `number`, while JSON Schema distinguishes `integer` from `number`.
 */
internal sealed interface WireType {
    data object Str : WireType

    /** `Int`, `Long`, `Short` and `Byte` are integers; `Float` and `Double` are not. */
    data class Num(
        val integer: Boolean,
    ) : WireType

    data object Bool : WireType

    /** `JsonElement`: any JSON at all, a `JsonNull` included. */
    data object Any : WireType

    /** `JsonObject`. */
    data object AnyObject : WireType

    /** `JsonArray`. */
    data object AnyArray : WireType

    /** `JsonPrimitive`: a string, a number or a boolean. */
    data object Primitive : WireType

    /** A declared interface, by its claimed name. */
    data class Ref(
        val name: String,
    ) : WireType

    /** A declared enum, by its claimed name. */
    data class EnumRef(
        val name: String,
    ) : WireType

    data class ArrayOf(
        val element: WireType,
    ) : WireType

    /**
     * A JSON object whose keys are not declared. [key] is [Str] for any primitive
     * key — a JSON key is a string whatever Kotlin called it — or an [EnumRef]
     * when the map is keyed by an enum; the walk refuses anything else.
     */
    data class MapOf(
        val key: WireType,
        val value: WireType,
    ) : WireType
}
```

- [ ] **Step 4: Add `TsRender`**

Create `backend/src/main/kotlin/ca/floo/roadtrip/apigen/TsRender.kt`, holding the primitive
names `DescriptorWalk` used to own:

```kotlin
package ca.floo.roadtrip.apigen

private const val TS_STRING = "string"

/** Every Kotlin number, including `Double` — and a `Double` on the wire is a *finite* one: the encoder rejects NaN/Infinity. */
private const val TS_NUMBER = "number"
private const val TS_BOOLEAN = "boolean"
private const val TS_UNKNOWN = "unknown"
private const val TS_UNKNOWN_RECORD = "Record<string, unknown>"
private const val TS_UNKNOWN_ARRAY = "unknown[]"
private const val TS_JSON_PRIMITIVE = "string | number | boolean"

/**
 * The TypeScript spelling of one [WireType].
 *
 * Both [WireType.Num] arms render `number`: the ids are database bigints that
 * stay well below 2^53, and TypeScript has no integer type to narrow them to.
 */
internal fun tsTypeOf(type: WireType): String =
    when (type) {
        WireType.Str -> TS_STRING
        is WireType.Num -> TS_NUMBER
        WireType.Bool -> TS_BOOLEAN
        WireType.Any -> TS_UNKNOWN
        WireType.AnyObject -> TS_UNKNOWN_RECORD
        WireType.AnyArray -> TS_UNKNOWN_ARRAY
        WireType.Primitive -> TS_JSON_PRIMITIVE
        is WireType.Ref -> type.name
        is WireType.EnumRef -> type.name
        is WireType.ArrayOf -> "${tsTypeOf(type.element)}[]"
        is WireType.MapOf -> "Record<${tsTypeOf(type.key)}, ${tsTypeOf(type.value)}>"
    }
```

- [ ] **Step 5: Make the walk structural**

In `backend/src/main/kotlin/ca/floo/roadtrip/apigen/DescriptorWalk.kt`, delete the seven
`TS_*` constants at lines 11-19 — they moved to `TsRender.kt`. Keep `JSON_PACKAGE`, the four
`JSON_*` serial names, `LIST_ELEMENT`, `INLINE_ELEMENT`, `MAP_KEY_ELEMENT`,
`MAP_VALUE_ELEMENT`, `DECLARED_NAME`, `Optionality` and `tsName` exactly as they are.

Replace `typeOf`:

```kotlin
    fun typeOf(
        descriptor: SerialDescriptor,
        within: String = descriptor.serialName,
    ): WireType {
        val target = descriptor.nonNullOriginal
        embeddedJsonType(target)?.let { return it }
        // A value class is encoded as the one value it wraps, so that is its wire type.
        if (target.isInline) return typeOf(target.getElementDescriptor(INLINE_ELEMENT), within)
        return when (val kind = target.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> WireType.Str
            PrimitiveKind.BOOLEAN -> WireType.Bool
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
                WireType.Num(integer = true)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> WireType.Num(integer = false)
            StructureKind.LIST -> WireType.ArrayOf(elementType(target, LIST_ELEMENT, within))
            StructureKind.MAP ->
                WireType.MapOf(mapKeyType(target, within), elementType(target, MAP_VALUE_ELEMENT, within))
            SerialKind.ENUM -> WireType.EnumRef(enumType(target))
            StructureKind.CLASS, StructureKind.OBJECT -> WireType.Ref(interfaceType(target))
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
```

`elementType` keeps its KDoc and its refusal message verbatim; only its return type changes
to `WireType` (its body already ends in `typeOf(element, within)`). `mapKeyType` keeps its
KDoc and refusal verbatim and returns a `WireType`:

```kotlin
    private fun mapKeyType(
        container: SerialDescriptor,
        within: String,
    ): WireType {
        val key = container.getElementDescriptor(MAP_KEY_ELEMENT)
        return when (val kind = key.nonNullOriginal.kind) {
            is PrimitiveKind -> WireType.Str
            SerialKind.ENUM -> WireType.EnumRef(enumType(key.nonNullOriginal))
            else ->
                throw ApiTypeGenerationException(
                    "$within is a map keyed by ${key.serialName}, whose serial kind is $kind. JSON " +
                        "object keys are strings, and roadtripApiJson refuses a structured key. Key " +
                        "the map by a primitive or an enum.",
                )
        }
    }
```

`interfaceType` and `enumType` keep their bodies and still return the claimed `String` name —
`typeOf` is what wraps them in a `Ref` or an `EnumRef`. `embeddedJsonType` returns a
`WireType?`:

```kotlin
    private fun embeddedJsonType(descriptor: SerialDescriptor): WireType? {
        if (!descriptor.serialName.startsWith(JSON_PACKAGE)) return null
        return when (descriptor.serialName) {
            JSON_ELEMENT -> WireType.Any
            JSON_OBJECT -> WireType.AnyObject
            JSON_ARRAY -> WireType.AnyArray
            JSON_PRIMITIVE -> WireType.Primitive
            else ->
                throw ApiTypeGenerationException(
                    "${descriptor.serialName} is a kotlinx.serialization.json type with no TypeScript " +
                        "mapping. JsonElement, JsonObject, JsonArray and JsonPrimitive are the four that map.",
                )
        }
    }
```

`isOptional` and `claimName` are untouched.

- [ ] **Step 6: Retype `TsField`**

In `backend/src/main/kotlin/ca/floo/roadtrip/apigen/TsTypes.kt`, one line:

```kotlin
/** One property of a generated interface. */
internal data class TsField(
    val name: String,
    val type: WireType,
    val optional: Boolean,
)
```

- [ ] **Step 7: Split the walk out of the generator**

In `backend/src/main/kotlin/ca/floo/roadtrip/apigen/ApiTypeGenerator.kt`, replace
`generateApiTypes` (lines 32-61) with a named walk plus a renderer over its result, moving
the row sort into the renderer. `GENERATED_HEADER`, `IDENTIFIER`, `descriptor()`, `merge`,
`render`, `renderEnum`, `renderEndpoints`, `quoted` and `key` keep their bodies.

```kotlin
/** Everything one walk of the contract produced. Rows stay in declaration order. */
internal data class ContractWalk(
    val interfaces: List<TsInterface>,
    val enums: List<TsEnum>,
    val rows: List<TsEndpoint>,
)

/**
 * One walk of the descriptor graph from every row of [endpoints], under both
 * optionality rules, sharing one name-collision map.
 *
 * The single source both renderers read: [generateApiTypes] writes the
 * TypeScript from it and [OpenApiContractDocument] writes `components/schemas`
 * and the per-operation bodies from the same result, so a schema can never name
 * a type the TypeScript does not declare. Rows are **not** sorted here: the
 * document builder zips them against `ApiContract.endpoints` positionally.
 */
internal fun walkContract(endpoints: List<ApiEndpoint> = ApiContract.endpoints): ContractWalk {
    val declaredBy = HashMap<String, String>()
    val responses = DescriptorWalk(Optionality.RESPONSE, declaredBy)
    val requests = DescriptorWalk(Optionality.REQUEST, declaredBy)
    // The row takes the names the walks claimed, so no consumer can name a type
    // the collision map has not approved.
    val rows =
        endpoints.map { endpoint ->
            TsEndpoint(
                method = endpoint.method.wireValue,
                path = endpoint.path,
                request = endpoint.request?.let { requests.declaredName(it) },
                response = endpoint.response?.let { responses.declaredName(it) },
                errors =
                    endpoint.errors
                        .map { responses.declaredName(it) }
                        .distinct()
                        .sorted(),
            )
        }
    return ContractWalk(
        interfaces = merge(responses.interfaces, requests.interfaces),
        enums = (responses.enums + requests.enums).values.toList(),
        rows = rows,
    )
}

/**
 * The whole generated file, for [endpoints]. Deterministic: declarations sorted
 * by name, fields in declaration order, endpoints sorted by path then method.
 */
internal fun generateApiTypes(endpoints: List<ApiEndpoint> = ApiContract.endpoints): String {
    val walk = walkContract(endpoints)
    return render(
        interfaces = walk.interfaces,
        enums = walk.enums,
        endpoints = walk.rows.sortedWith(compareBy({ it.path }, { it.method })),
    )
}

/** The claimed declaration name for a contract row's class, walking it on first mention. */
private fun DescriptorWalk.declaredName(kClass: KClass<*>): String =
    when (val type = typeOf(kClass.descriptor())) {
        is WireType.Ref -> type.name
        is WireType.EnumRef -> type.name
        else ->
            throw ApiTypeGenerationException(
                "${kClass.qualifiedName} is named by a contract row, but its descriptor is a $type " +
                    "rather than a declared class or enum. A request, response or error body must be a " +
                    "@Serializable class or enum.",
            )
    }
```

and in `renderInterface`, the field line renders through `tsTypeOf`:

```kotlin
            append("  ${key(field.name)}${if (field.optional) "?" else ""}: ${tsTypeOf(field.type)};\n")
```

- [ ] **Step 8: Run the new test and the old one**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.apigen.*' --offline -q`
Expected: PASS. `WireTypeTest` proves the structure; `ApiTypeGeneratorTest` — every one of its
cases, unchanged — proves every rendered string and every refusal still comes out the same.

- [ ] **Step 9: Prove the committed TypeScript is byte-identical**

Run: `./gradlew :backend:checkApiTypes --offline -q`
Expected: `frontend/src/api/generated/api-types.ts is up to date`. Nothing under `frontend/`
changed in this task. If this fails, `tsTypeOf` is not a faithful transcription of the strings
`DescriptorWalk` used to return — fix the renderer, never regenerate the file.

- [ ] **Step 10: Run the backend gate**

Run: `./gradlew :backend:test :backend:koverXmlReport :backend:koverVerify :backend:ktlintCheck :backend:detekt :backend:checkApiTypes --offline -q`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/apigen/WireType.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/apigen/TsRender.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/apigen/DescriptorWalk.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/apigen/TsTypes.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/apigen/ApiTypeGenerator.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/apigen/WireTypeTest.kt
git commit -F /dev/stdin <<'MSG'
refactor(apigen): the descriptor walk reports a wire type, not TypeScript text

typeOf returns WireType, TsField.type is a WireType, and TsRender turns it back
into the same strings the walk used to return. checkApiTypes passing against the
committed file untouched is the proof: the generated TypeScript is byte-identical.
Num carries the one distinction TypeScript cannot — integer versus fractional —
for the JSON Schema renderer that follows.

The walk itself gains a name. walkContract returns interfaces, enums and rows in
declaration order and generateApiTypes renders over its result, so the schema
renderer and the OpenAPI document read one walk and cannot disagree with the
TypeScript about a declared name.

Refs #754

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---
### Task 2: statuses in the contract — 46 rows, `ApiBody`, and the new `API_ENDPOINTS` shape

`ApiEndpoint.response: KClass<*>?` becomes `success: ApiBody` and `errors: List<KClass<*>>`
becomes `List<ApiBody>`. Every one of the 46 rows states the status of every body it can
serve, read off the route file. The generated TypeScript row shape changes with it, which is
the one place in this phase where `api-types.ts` is regenerated on purpose.

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiStatus.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiContract.kt` (whole file), `backend/src/main/kotlin/ca/floo/roadtrip/apigen/TsTypes.kt:22-33`, `backend/src/main/kotlin/ca/floo/roadtrip/apigen/ApiTypeGenerator.kt:10-27,42-55,128-140`, `frontend/src/api/generated/api-types.ts` (regenerated)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/route/common/ApiContractCoverageTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/apigen/ApiTypeGeneratorTest.kt:434-478`, `frontend/src/api/api-endpoints.test.ts`

**Interfaces:**
- Consumes: `ca.floo.roadtrip.apigen.walkContract`, `ContractWalk`, `WireType`, `tsTypeOf` (Task 1); every `@Serializable` DTO the table below names.
- Produces:
  - `ca.floo.roadtrip.model.api.HTTP_OK` … `HTTP_SERVICE_UNAVAILABLE` — `const val <name>: Int`, top level in `ca.floo.roadtrip.model.api`
  - `ca.floo.roadtrip.model.api.ApiBody` — `data class ApiBody(val status: Int, val body: KClass<*>? = null)`
  - `ca.floo.roadtrip.model.api.ApiEndpoint` — `data class ApiEndpoint(val method: ApiMethod, val path: String, val request: KClass<*>? = null, val success: ApiBody, val errors: List<ApiBody> = listOf(ApiBody(HTTP_BAD_REQUEST, ApiErrorSchema::class)), val conditional: Boolean = false)`
  - `ca.floo.roadtrip.model.api.apiErrors` — `fun apiErrors(vararg statuses: Int): List<ApiBody>`
  - `ca.floo.roadtrip.model.api.bodyAt` — `fun ApiEndpoint.bodyAt(status: Int): ApiBody?`
  - `ca.floo.roadtrip.model.api.ApiContract` — `object ApiContract { val endpoints: List<ApiEndpoint>; fun keys(): Set<Pair<String, String>>; fun requiredKeys(): Set<Pair<String, String>> }` (the two key functions unchanged)
  - `ca.floo.roadtrip.apigen.TsBody` — `internal data class TsBody(val status: Int, val type: String?)`
  - `ca.floo.roadtrip.apigen.TsEndpoint` — `internal data class TsEndpoint(val method: String, val path: String, val request: String?, val success: TsBody, val errors: List<TsBody>)`

#### The 46 rows, with every status and the line that serves it

`ApiErrorSchema` is abbreviated `ApiError`. `400 ApiError` on a row with no other 400 is the
universal `StatusPages` default (`RoadtripRouting.kt:62-90`) rather than a line in the
handler. Line numbers are as of `3aa375c1`. The **Access** column is what the route declares;
the document builder adds 401 for `User` and 401 + 403 for `HasRole`, so those never appear in
the Errors column — but a 401 or 403 the handler itself writes does (Resolution 6).

| # | Method | Path | Request | Success | Errors (status → class) | Access | Evidence |
|---|---|---|---|---|---|---|---|
| 1 | POST | `/auth/password/begin` | `PasswordBeginRequestDto` | 200 `PasswordBeginResponseDto` | 400 `ApiError`; 502 `ApiError`; 503 `ApiError` | Anonymous | `AuthRoutes.kt:140` (502), `:134`→`:251-256` (503) |
| 2 | POST | `/auth/password/complete` | `PasswordCompleteRequestDto` | **204** — | 400 `ApiError`; **401** `ApiError`; 503 `ApiError` | Anonymous | `AuthRoutes.kt:178` (204), `:163` (400), `:170` (401), `:154`→`:251` (503) |
| 3 | GET | `/api/me` | | 200 `MeResponseDto` | 400 `ApiError` | Anonymous | `AuthRoutes.kt:183-204` |
| 4 | GET | `/api/settings` | | 200 `SettingsResponseDto` | 400, 409, 502, 503 `ApiError` | User | `SettingsRoutes.kt:41-46`; `SettingsErrorResponses.kt:59-68` |
| 5 | PUT | `/api/settings/profile` | `UpdateProfileRequest` | 200 `SettingsResponseDto` | 400, 409, 502, 503 `ApiError` | User | `SettingsRoutes.kt:48-58`; same mapper |
| 6 | PUT | `/api/settings/notifications` | `UpdateNotificationsRequest` | 200 `SettingsResponseDto` | 400, 409, 502, 503 `ApiError` | User | `SettingsRoutes.kt:60-75`; same mapper |
| 7 | DELETE | `/api/settings/notifications/slack` | | 200 `SettingsResponseDto` | 400, 409, 502, 503 `ApiError` | User | `SettingsRoutes.kt:77-82`; same mapper |
| 8 | POST | `/api/settings/notifications/slack/test` | `SlackTestRequest` | 200 `SlackTestResponseDto` | 400, 409, 502, 503 `ApiError` | User | `SettingsRoutes.kt:84-97`; same mapper |
| 9 | POST | `/api/settings/notifications/email/test` | | 200 `EmailTestResponseDto` | 400, 409, 502, 503 `ApiError` | User | `SettingsRoutes.kt:99-103`; same mapper |
| 10 | PUT | `/api/settings/recgov` | `UpdateRecgovRequest` | 200 `BookingSettingsDto` | 400, 409, 502, 503 `ApiError` | User | `RecgovSettingsRoutes.kt:46-56`; same mapper |
| 11 | DELETE | `/api/settings/recgov` | | 200 `RecgovRemovedDto` | 400, 409, 502, 503 `ApiError` | User | `RecgovSettingsRoutes.kt:58-62`; same mapper |
| 12 | POST | `/api/settings/recgov/login` | | 200 `RecgovLoginResponseDto` | 400, 409, 502, 503 `ApiError` | User | `RecgovSettingsRoutes.kt:64-68`; same mapper |
| 13 | POST | `/api/settings/recgov/login/mfa` | `RecgovMfaRequest` | 200 `RecgovLoginResponseDto` | 400, 409, 502, 503 `ApiError` | User | `RecgovSettingsRoutes.kt:70-80`; same mapper |
| 14 | POST | `/api/settings/recgov/verify` | | 200 `RecgovVerifyResponseDto` | 400, 409, 502, 503 `ApiError` | User | `RecgovSettingsRoutes.kt:82-86`; same mapper |
| 15 | GET | `/api/settings/recgov/status` | | 200 `RecgovStatusDto` | 400, 409, 502, 503 `ApiError` | User | `RecgovSettingsRoutes.kt:88-92`; same mapper |
| 16 | POST | `/api/booking/add-to-cart` | `AddToCartRequestDto` | 200 `AddToCartResponseDto` | 400 `ApiError`; **403** `ApiError`; 409 `ApiError`; 422 `ApiError`; 502 `ApiError` | User | `BookingRoutes.kt:55,60` (400), `:120,127` (403), `:121,128` (409), `:130` (422), `:122` (502) |
| 17 | POST | `/api/pois` | `PoisRequestSchema` | 200 `PoiFeatureCollectionSchema` | 400 `ApiError` | Anonymous | `PoiRoutes.kt:58-62` |
| 18 | GET | `/api/pois/search` | | 200 `PoiSearchResponseSchema` | 400 `ApiError` | Anonymous | `PoiRoutes.kt:89-99` |
| 19 | GET | `/api/pois/{id}` | | 200 `PoiDetailFeatureSchema` | 400 `ApiError`; 404 `ApiError` | Anonymous | `PoiRoutes.kt:118` (400), `:121` (404) |
| 20 | POST | `/api/pois/on-route` | `OnRouteRequestDto` | 200 `PoisOnRouteResponseSchema` | 400 `ApiError`; 503 `ApiError` | Anonymous | `PoisOnRouteRoutes.kt:52-55` (400), `:70-73` (503) |
| 21 | POST | `/api/pois/availability/bulk` | `BulkAvailabilityRequestDto` | 200 `BulkAvailabilityResponseDto` | 400 `AvailabilityErrorDto`; 503 `AvailabilityErrorDto` | Anonymous | `BulkAvailabilityRoutes.kt:56,67,80` (400), `:49` (503), via `respondBulkError` `:140-146` |
| 22 | GET | `/api/pois/{id}/campsites` | | 200 `PoiCampsitesResponseSchema` | 400 `ApiError`; 404 `ApiError` | Anonymous | `CampsiteRoutes.kt:52,58` (400), `:68` (404) |
| 23 | GET | `/api/pois/{id}/campsites/availability` | | 200 `PoiCampsitesAvailabilityResponseDto` | 400, 404, 500, 501, 503 all `AvailabilityErrorDto` | Anonymous | `CampsiteRoutes.kt:81,93,105,112,211` (400), `:212,213` (404), `:172` (500), `:169` (501), `:84,168` (503) |
| 24 | GET | `/api/watches` | | 200 `AvailabilityWatchListResponse` | 400 `ApiError` | User | `AvailabilityWatchRoutes.kt:49-53` |
| 25 | POST | `/api/watches` | `AvailabilityWatchCreateRequest` | **201** `AvailabilityWatchResponse` | 400 `ApiError`; **403** `ApiError`; 404 `ApiError`; 500 `ApiError` | User | `:76` (201), `:70,74,156` (400), `:158` (403), `:162` (404), `:154` (500) |
| 26 | GET | `/api/watches/{id}` | | 200 `AvailabilityWatchResponse` | 400 `ApiError`; **401** `ApiError`; **403** `ApiError`; 404 `ApiError`; 500 `ApiError` | UserOrCapability | `:92,156` (400), `:160` (401), `:158` (403), `:162` (404), `:154` (500) |
| 27 | POST | `/api/watches/{id}/modify` | `AvailabilityWatchUpdateRequest` | 200 `AvailabilityWatchResponse` | 400 `ApiError`; **401** `ApiError`; **403** `ApiError`; 404 `ApiError`; 500 `ApiError` | UserOrCapability | `:100,104,108,156` (400), `:160`, `:158`, `:162`, `:154` |
| 28 | POST | `/api/watches/{id}/delete` | | **204** — | 400 `ApiError`; **401** `ApiError`; **403** `ApiError`; 404 `ApiError`; 500 `ApiError` | UserOrCapability | `:119` (204), `:117,156` (400), `:160`, `:158`, `:162`, `:154` |
| 29 | GET | `/api/availability/pollers` | | 200 `AvailabilityPollersListResponse` | 400 `ApiError` | Anonymous | `AvailabilityDashboardRoutes.kt:43-47` |
| 30 | GET | `/api/availability/pollers/summary` | | 200 `AvailabilityPollersSummary` | 400 `ApiError` | Anonymous | `AvailabilityDashboardRoutes.kt:58-63` |
| 31 | GET | `/api/availability/pollers/{id}/runs` | | 200 `AvailabilityRunsListResponse` | 400 `ApiError` | Anonymous | `AvailabilityDashboardRoutes.kt:69` |
| 32 | POST | `/api/availability/pollers/{id}/force` | | 200 `CheckNowResponseDto` | 400 `ApiError`; 404 `ApiError`; **429 `CheckNowCooldownDto`** | Anonymous | `:78` (400), `:84` (404), `:82` (429) |
| 33 | GET | `/api/availability/runs` | | 200 `AvailabilityRunsListResponse` | 400 `ApiError` | Anonymous | `AvailabilityDashboardRoutes.kt:92-97` |
| 34 | GET | `/api/availability/changes` | | 200 `ListAvailabilityChangesResponse` | 400 `ApiError`; 404 `ApiError` | Anonymous | `:118` (400), `:120` (404) |
| 35 | GET | `/api/availability/changes/summary` | | 200 `AvailabilitySnapshotsSummaryResponse` | 400 `ApiError`; 404 `ApiError` | Anonymous | `:138` (400), `:140` (404) |
| 36 | GET | `/api/route` | | 200 `RouteFeatureCollectionDto` | 400 `RouteErrorDto`; 503 `RouteErrorDto` | Anonymous | `RouteRoutes.kt:54,62,74,84,92,105,115,127` (400), `:42,133,139` (503), via `:148-154`. No `ApiError` 400: `trimmedQuery` never throws (`RouteQueryParams.kt:13`) |
| 37 | GET | `/api/geocode` | | 200 `GeocodeResponseDto` | 400 `ApiError`; 503 `ApiError` | Anonymous | `GeocodeRoutes.kt:50` (400), `:47,51` (503) |
| 38 | GET | `/api/build-info` | | 200 `BuildInfoDto` | 400 `ApiError` | Anonymous | `BuildInfoRoutes.kt:15-18` |
| 39 | GET | `/api/health` | | 200 `HealthResponseDto` | 400 `ApiError` | Anonymous | `HealthRoutes.kt:32-35` |
| 40 | GET | `/api/health/ready` | | 200 `ReadinessResponseDto` | 400 `ApiError`; **503 `ReadinessResponseDto`** | Anonymous | `HealthRoutes.kt:41` — same DTO at both statuses |
| 41 | POST | `/api/admin/data/import` | | 200 `FanOutResponseSchema` | 400 `ApiError`; **500 `FanOutResponseSchema`** | Anonymous | `AdminIngestRoutes.kt:186-193` |
| 42 | POST | `/api/admin/data/import/{target}` | | 200 `RunOutcomeSchema` | 400 `ApiError`; 404 `ErrorUnknownTargetSchema`; 409 `ErrorTargetBusySchema`; **500 `RunOutcomeSchema`** | Anonymous | `:115-116` (500), `:119-122` (404), `:123-127` (409) |
| 43 | GET | `/api/admin/data/runs` | | 200 `RunsListSchema` | 400 `ApiError` | Anonymous | `AdminIngestRoutes.kt:74-78` |
| 44 | GET | `/api/admin/data/runs/{id}` | | 200 `RunDetailSchema` | **400 `ErrorNotFoundSchema`**; 404 `ErrorNotFoundSchema` | Anonymous | `:83` (400), `:88` (404). `longPath` returns null rather than throwing, so no `ApiError` 400 |
| 45 | GET | `/api/admin/data/status` | | 200 `StatusResponseSchema` | 400 `ApiError` | Anonymous | `AdminIngestRoutes.kt:96-99` |
| 46 | POST | `/api/slack/interactivity` | | 200 — (empty `text/plain`) | *(none)* | Signed, `conditional` | `SlackInteractivityRoutes.kt:115` (200), `:81` (401), `:88,98` (400) — all `respondText("")`, so no JSON body the check or the document can describe |

Forty-six rows, unchanged in count. `/auth/login`, `/auth/callback` and `/auth/logout` are
redirect-only and sit outside both covered prefixes; `/api/docs/**` is exempt.

- [ ] **Step 1: Write the failing contract tests**

In `backend/src/test/kotlin/ca/floo/roadtrip/route/common/ApiContractCoverageTest.kt`, add
these five cases after `every contract path is one the guard is answerable for`, and add
`import ca.floo.roadtrip.model.api.ApiErrorSchema` and `import kotlin.test.assertNull`:

```kotlin
    @Test
    fun `every declared status is a real HTTP status`() {
        assertEquals(
            emptyList(),
            ApiContract.endpoints
                .flatMap { row -> (listOf(row.success) + row.errors).map { row.method.wireValue to it } }
                .filterNot { (_, body) -> body.status in HTTP_OK..MAX_HTTP_STATUS }
                .map { (method, body) -> "$method ${body.status}" },
        )
    }

    @Test
    fun `the success body is the only 2xx a row declares`() {
        ApiContract.endpoints.forEach { row ->
            assertTrue(
                row.success.status in HTTP_OK..LAST_SUCCESS_STATUS,
                "${row.method.wireValue} ${row.path}: success declares ${row.success.status}, not a 2xx",
            )
            assertEquals(
                emptyList(),
                row.errors.filter { it.status in HTTP_OK..LAST_SUCCESS_STATUS }.map { it.status },
                "${row.method.wireValue} ${row.path}: a 2xx belongs in success, not errors",
            )
        }
    }

    @Test
    fun `no row declares the same status and class twice`() {
        ApiContract.endpoints.forEach { row ->
            val keys = (listOf(row.success) + row.errors).map { it.status to it.body }
            assertEquals(
                keys.size,
                keys.toSet().size,
                "${row.method.wireValue} ${row.path} declares a duplicate (status, class): " +
                    "${keys.groupBy { it }.filterValues { it.size > 1 }.keys}",
            )
        }
    }

    @Test
    fun `a 204 carries no body`() {
        ApiContract.endpoints
            .flatMap { row -> (listOf(row.success) + row.errors).map { row to it } }
            .filter { (_, body) -> body.status == HTTP_NO_CONTENT }
            .forEach { (row, body) ->
                assertNull(body.body, "${row.method.wireValue} ${row.path}: a 204 cannot carry a body")
            }
    }

    /**
     * The checkable half of the 401/403 rule: the document builder supplies those
     * statuses from the route's access level, and a row states one only where its
     * own handler writes it — which, everywhere in this tree, means
     * [ApiErrorSchema]. A row naming anything else at 401 or 403 is a row that
     * has started restating the access guard in its own vocabulary.
     */
    @Test
    fun `a 401 or 403 on a row is always an ApiErrorSchema`() {
        assertEquals(
            emptyList(),
            ApiContract.endpoints
                .flatMap { row -> row.errors.map { row to it } }
                .filter { (_, body) -> body.status == HTTP_UNAUTHORIZED || body.status == HTTP_FORBIDDEN }
                .filterNot { (_, body) -> body.body == ApiErrorSchema::class }
                .map { (row, body) -> "${row.method.wireValue} ${row.path} ${body.status}" },
        )
    }
```

with the two bounds as named constants beside `CONTRACT_ROW_COUNT` at the top of the file:

```kotlin
/** The last status any HTTP response can carry, and the last 2xx. */
private const val MAX_HTTP_STATUS = 599
private const val LAST_SUCCESS_STATUS = 299
```

and change `every class the contract names is serializable` to read the new shape:

```kotlin
        val classes =
            ApiContract.endpoints
                .flatMap { listOfNotNull(it.request, it.success.body) + it.errors.mapNotNull { body -> body.body } }
                .distinct()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.common.ApiContractCoverageTest' --offline -q`
Expected: FAIL to compile — `Unresolved reference: success`, `HTTP_OK`, `HTTP_NO_CONTENT`,
`HTTP_UNAUTHORIZED`, `HTTP_FORBIDDEN`, and `ApiBody`.

- [ ] **Step 3: Add `ApiStatus.kt`**

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiStatus.kt`:

```kotlin
package ca.floo.roadtrip.model.api

/**
 * The statuses [ApiContract] rows declare.
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
```

- [ ] **Step 4: Rewrite `ApiContract.kt`**

Replace `backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiContract.kt` entirely. Every
argument is on its own line: ktlint's `argument-list-wrapping` requires it once a call wraps,
and a one-line row would run past 140 characters for most paths.

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
 * [conditional] marks a route mounted only under some configuration, which the
 * boot guard must not require and the document builder skips when absent.
 */
data class ApiEndpoint(
    val method: ApiMethod,
    val path: String,
    val request: KClass<*>? = null,
    val success: ApiBody,
    val errors: List<ApiBody> = listOf(ApiBody(HTTP_BAD_REQUEST, ApiErrorSchema::class)),
    val conditional: Boolean = false,
)

/** [ApiErrorSchema] at each of [statuses] — the shape 31 of the 46 rows need. */
fun apiErrors(vararg statuses: Int): List<ApiBody> = statuses.map { ApiBody(it, ApiErrorSchema::class) }

/**
 * What this endpoint declares at [status], success or error; null when it
 * declares nothing there. A top-level extension rather than a member because
 * detekt's `DataClassContainsFunctions` allows only `to`/`as` prefixes on a data
 * class.
 */
fun ApiEndpoint.bodyAt(status: Int): ApiBody? = (listOf(success) + errors).firstOrNull { it.status == status }

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
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/pois/search",
                success = ApiBody(HTTP_OK, PoiSearchResponseSchema::class),
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
                ApiMethod.GET,
                "/api/watches",
                success = ApiBody(HTTP_OK, AvailabilityWatchListResponse::class),
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
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/availability/pollers/summary",
                success = ApiBody(HTTP_OK, AvailabilityPollersSummary::class),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/availability/pollers/{id}/runs",
                success = ApiBody(HTTP_OK, AvailabilityRunsListResponse::class),
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
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/health",
                success = ApiBody(HTTP_OK, HealthResponseDto::class),
            ),
            ApiEndpoint(
                ApiMethod.GET,
                "/api/health/ready",
                // The readiness probe answers the same DTO at both statuses: 503 is the
                // load balancer's signal, not a different shape.
                success = ApiBody(HTTP_OK, ReadinessResponseDto::class),
                errors =
                    apiErrors(HTTP_BAD_REQUEST) +
                        ApiBody(HTTP_SERVICE_UNAVAILABLE, ReadinessResponseDto::class),
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
```

`watchRefusals` is a private *top-level* val rather than a member of `ApiContract`, because
`ClassOrdering` wants properties before the object's functions and a forward reference from
`endpoints` to a later member property would be an uninitialised read; `settingsErrors` is
declared above `endpoints` and so can live inside. Both match detekt's
`privatePropertyPattern`, so neither needs a `@Suppress`.

- [ ] **Step 5: Retype the emitted endpoint row**

In `backend/src/main/kotlin/ca/floo/roadtrip/apigen/TsTypes.kt`, replace `TsEndpoint`:

```kotlin
/** One declared body in an emitted `API_ENDPOINTS` row: the status, and the type it carries. */
internal data class TsBody(
    val status: Int,
    val type: String?,
)

/**
 * One row of the emitted `API_ENDPOINTS` literal. [errors] carries the non-2xx
 * bodies the route can serialize, sorted by status then type so the row does not
 * move when the contract list is reordered.
 */
internal data class TsEndpoint(
    val method: String,
    val path: String,
    val request: String?,
    val success: TsBody,
    val errors: List<TsBody>,
)
```

In `backend/src/main/kotlin/ca/floo/roadtrip/apigen/ApiTypeGenerator.kt`, the row build inside
`walkContract`:

```kotlin
            TsEndpoint(
                method = endpoint.method.wireValue,
                path = endpoint.path,
                request = endpoint.request?.let { requests.declaredName(it) },
                success =
                    TsBody(
                        endpoint.success.status,
                        endpoint.success.body?.let { responses.declaredName(it) },
                    ),
                errors =
                    endpoint.errors
                        .map { body -> TsBody(body.status, body.body?.let { responses.declaredName(it) }) }
                        .distinct()
                        .sortedWith(compareBy({ it.status }, { it.type.orEmpty() })),
            )
```

the renderer:

```kotlin
private fun renderEndpoints(endpoints: List<TsEndpoint>): String =
    buildString {
        append("export const API_ENDPOINTS = [\n")
        endpoints.forEach { endpoint ->
            append("  { method: '${endpoint.method}', path: '${endpoint.path}'")
            append(", request: ${quoted(endpoint.request)}")
            append(", success: ${renderBody(endpoint.success)}")
            append(", errors: [${endpoint.errors.joinToString(transform = ::renderBody)}] },\n")
        }
        append("] as const;\n")
    }

private fun renderBody(body: TsBody): String = "{ status: ${body.status}, type: ${quoted(body.type)} }"
```

and three lines appended to `GENERATED_HEADER`, before its closing `"""`:

```
//
// Each API_ENDPOINTS row states the status of every body it can serve. 401 and
// 403 are absent wherever the route's declared access level supplies them; see
// /api/docs/openapi.json for the merged picture.
```

- [ ] **Step 6: Fix the generator test's row helpers and literal assertion**

In `backend/src/test/kotlin/ca/floo/roadtrip/apigen/ApiTypeGeneratorTest.kt`, add
`import ca.floo.roadtrip.model.api.ApiBody`, `import ca.floo.roadtrip.model.api.HTTP_NO_CONTENT`
and `import ca.floo.roadtrip.model.api.HTTP_OK`, then replace the two helpers:

```kotlin
    private fun responseRow(kClass: KClass<*>): ApiEndpoint =
        ApiEndpoint(
            ApiMethod.GET,
            "/api/fixture/${kClass.simpleName}",
            success = ApiBody(HTTP_OK, kClass),
            errors = emptyList(),
        )

    private fun requestRow(kClass: KClass<*>): ApiEndpoint =
        ApiEndpoint(
            ApiMethod.POST,
            "/api/fixture/${kClass.simpleName}",
            request = kClass,
            success = ApiBody(HTTP_NO_CONTENT),
            errors = emptyList(),
        )
```

and rewrite `the endpoint literal names the request, response and error types` as:

```kotlin
    @Test
    fun `the endpoint literal names the request, the success body and every error status`() {
        val ts =
            generateApiTypes(
                listOf(
                    ApiEndpoint(
                        ApiMethod.PATCH,
                        "/api/fixtures",
                        FixtureOptionality::class,
                        ApiBody(HTTP_CREATED, FixtureShapes::class),
                        errors =
                            listOf(
                                ApiBody(HTTP_CONFLICT, FixtureEncodeAlways::class),
                                ApiBody(HTTP_BAD_REQUEST, FixtureOddKeys::class),
                            ),
                    ),
                    responseRow(FixtureNested::class),
                ),
            )
        assertTrue(
            ts.contains(
                "{ method: 'PATCH', path: '/api/fixtures', request: 'FixtureOptionality', " +
                    "success: { status: 201, type: 'FixtureShapes' }, errors: " +
                    "[{ status: 400, type: 'FixtureOddKeys' }, { status: 409, type: 'FixtureEncodeAlways' }] },",
            ),
            ts,
        )
        assertTrue(
            ts.contains(
                "{ method: 'GET', path: '/api/fixture/FixtureNested', request: null, " +
                    "success: { status: 200, type: 'FixtureNested' }, errors: [] },",
            ),
            ts,
        )
        // An error body is walked like any other: its declaration is emitted too.
        assertTrue(ts.contains("export interface FixtureOddKeys {"), ts)
        assertTrue(ts.trimEnd().endsWith("] as const;"), ts)
    }

    @Test
    fun `a body-less success emits a null type`() {
        val ts = generateApiTypes(listOf(requestRow(FixtureOptionality::class)))
        assertTrue(ts.contains("success: { status: 204, type: null }, errors: [] },"), ts)
    }
```

adding `import ca.floo.roadtrip.model.api.HTTP_BAD_REQUEST`,
`import ca.floo.roadtrip.model.api.HTTP_CONFLICT` and
`import ca.floo.roadtrip.model.api.HTTP_CREATED`. The errors list is written out of order on
purpose: the assertion proves the renderer sorts by status, not by declaration.

- [ ] **Step 7: Run the backend half**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.apigen.*' --tests 'ca.floo.roadtrip.route.common.ApiContractCoverageTest' --offline -q`
Expected: PASS.

- [ ] **Step 8: Regenerate the committed TypeScript**

Run: `./gradlew :backend:generateApiTypes --offline -q`
Then: `git diff --stat frontend/src/api/generated/api-types.ts`
Expected: the header gains three lines and all 46 `API_ENDPOINTS` rows change shape. **No
`export interface` or `export type` line may change** — confirm with
`git diff -U0 frontend/src/api/generated/api-types.ts | grep '^[-+]export' | sort | uniq -c`,
which must print nothing. Any declaration diff means Task 1's refactor was not faithful.

- [ ] **Step 9: Add the frontend companion assertion**

In `frontend/src/api/api-endpoints.test.ts`, add the declared-name regex beside the others and
the test at the end of the file:

```ts
/** A generated declaration, read as source: the contract's own list of legal type names. */
const DECLARED_TYPE = /^export (?:interface|type) (\w+)/gm;
const GENERATED_TYPES = join(API_DIR, 'generated/api-types.ts');

test('every type a contract row names is a declared interface or union', () => {
  const declared = new Set(
    [...readFileSync(GENERATED_TYPES, 'utf8').matchAll(DECLARED_TYPE)].map((match) => match[1]),
  );
  const named: (string | null)[] = [];
  for (const row of API_ENDPOINTS) {
    named.push(row.request, row.success.type);
    for (const entry of row.errors) named.push(entry.type);
  }
  const undeclared = [...new Set(named.filter((name): name is string => name !== null))]
    .filter((name) => !declared.has(name))
    .sort();
  expect(undeclared, 'a row names a type this file does not declare').toEqual([]);
});

test('every declared status is a plausible HTTP status', () => {
  const statuses = API_ENDPOINTS.flatMap((row) => [
    row.success.status,
    ...row.errors.map((entry) => entry.status),
  ]);
  expect(statuses.filter((status) => status < 200 || status > 599)).toEqual([]);
});
```

- [ ] **Step 10: Run the frontend gate**

Run: `cd frontend && npm run typecheck && npm run test && npm run lint`
Expected: PASS. `api-endpoints.test.ts`'s two existing cases read `row.path` only and are
untouched by the row-shape change.

- [ ] **Step 11: Run the backend gate**

Run: `./gradlew :backend:test :backend:koverXmlReport :backend:koverVerify :backend:ktlintCheck :backend:detekt :backend:checkApiTypes --offline -q`
Expected: PASS — `checkApiTypes` now compares against the file Step 8 wrote.

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiStatus.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiContract.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/apigen/TsTypes.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/apigen/ApiTypeGenerator.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/apigen/ApiTypeGeneratorTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/common/ApiContractCoverageTest.kt \
        frontend/src/api/generated/api-types.ts \
        frontend/src/api/api-endpoints.test.ts
git commit -F /dev/stdin <<'MSG'
feat(api): every contract row states the status of every body it serves

ApiEndpoint.response becomes success: ApiBody and errors becomes List<ApiBody>,
so the contract records what /api/pois/{id}/campsites/availability answers at
each of 400, 404, 500, 501 and 503; that POST /api/watches is a 201 and its
delete a 204; that the readiness probe serves the same DTO at 200 and 503; that
two admin routes serve their 2xx DTO again at 500; and that the force-poller row
carries CheckNowCooldownDto at 429. The statuses are named constants in
model/api/ApiStatus.kt for the same reason ApiMethod exists: model/ may not name
Ktor, and a bare 201 at a call site is the magic constant AGENTS.md forbids.

401 and 403 stay off a row whose access level supplies them. Where a handler
writes one itself — the UserOrCapability watch routes, the anonymous
password/complete, add-to-cart's richer refusal vocabulary — the row says so, and
ApiContractCoverageTest pins that every such entry is an ApiErrorSchema.

API_ENDPOINTS rows become { method, path, request, success: { status, type },
errors: [{ status, type }] }. No generated interface or union changed.

Refs #754

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---
### Task 3: `JsonSchemaRender` — the second view of the same walk

The mapping table from Global Constraints, as `io.ktor.openapi.JsonSchema`, over the same
`ContractWalk` the TypeScript renderer reads. Nothing is wired into a route yet: this task ends
with a renderer and its tests, and `/api/docs/openapi.json` unchanged.

The Ktor 3.5.2 model facts this relies on, each verified against the published sources of
`io.ktor:ktor-openapi-schema-jvm:3.5.2`:

- `JsonSchema` is an all-defaults `@Serializable data class`. Under `roadtripApiJson`
  (`explicitNulls = false`) every unset property is omitted, so `JsonSchema()` encodes as `{}`.
- `JsonSchema.type` is a `JsonSchema.SchemaType?`, a sealed interface with two implementers:
  the `JsonType` enum (`ARRAY`, `OBJECT`, `NUMBER`, `BOOLEAN`, `INTEGER`, `NULL`, `STRING`) and
  `JsonSchema.SchemaType.AnyOf(types: List<JsonType>)`. `SchemaType.Serializer` encodes a
  `JsonType` as its lowercased name and an `AnyOf` as an array of them, which is exactly the
  `[string, number, boolean]` the `JsonPrimitive` row needs.
- `JsonSchema.additionalProperties` is an `AdditionalProperties?`, a sealed interface of two
  `value class`es: `Allowed(Boolean)`, which encodes as a bare boolean, and
  `PSchema(ReferenceOr<JsonSchema>)`, which encodes as the schema.
- `JsonSchema.enum` is a `List<GenericElement?>?`; `GenericElementString(value: String)` is the
  public wrapper for a string constant.
- `JsonSchema.properties` is a `Map<String, ReferenceOr<JsonSchema>>?` and `items` a
  `ReferenceOr<JsonSchema>?`. `ReferenceOr.schema(name)` builds
  `Reference("#/components/schemas/$name")` and `ReferenceOr.value(x)` the inline value arm.
- **`JsonSchema` has no `propertyNames`** (Resolution 1), so the spec's `propertyNames: {$ref}`
  for an enum-keyed map is not expressible and is documented instead.
- Property order in the encoded object is `JsonSchema`'s constructor order, so `type` precedes
  `title`, `required`, `properties`, `additionalProperties`, `items` and `enum`.

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/apigen/JsonSchemaRender.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/apigen/JsonSchemaRenderTest.kt` (new)

**Interfaces:**
- Consumes: `ca.floo.roadtrip.apigen.WireType`, `ContractWalk`, `walkContract`, `TsInterface`, `TsEnum`, `TsField` (Tasks 1-2); `io.ktor.openapi.{JsonSchema, JsonType, AdditionalProperties, GenericElementString, ReferenceOr}`.
- Produces:
  - `ca.floo.roadtrip.apigen.jsonSchemaOf` — `internal fun jsonSchemaOf(type: WireType): ReferenceOr<JsonSchema>`
  - `ca.floo.roadtrip.apigen.schemasOf` — `internal fun schemasOf(walk: ContractWalk): Map<String, JsonSchema>`
  - `ca.floo.roadtrip.apigen.contractSchemas` — `internal fun contractSchemas(endpoints: List<ApiEndpoint> = ApiContract.endpoints): Map<String, JsonSchema>`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/apigen/JsonSchemaRenderTest.kt`. It asserts
the *encoded* JSON under the one encoder, so it proves the model construction and Ktor's own
serializers together; trees rather than strings, so a property reordering inside `JsonSchema`
is not a test failure but a missing or spurious key is:

```kotlin
package ca.floo.roadtrip.apigen

import ca.floo.roadtrip.model.api.ApiBody
import ca.floo.roadtrip.model.api.ApiEndpoint
import ca.floo.roadtrip.model.api.ApiMethod
import ca.floo.roadtrip.model.api.HTTP_NO_CONTENT
import ca.floo.roadtrip.model.api.HTTP_OK
import ca.floo.roadtrip.route.common.encodeApiJson
import io.ktor.openapi.JsonSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JSON-Schema view of the walk, asserted as what `roadtripApiJson` actually
 * writes — the encoder the document is served through, whose `explicitNulls =
 * false` is what turns a 39-property data class into a two-key schema.
 */
class JsonSchemaRenderTest {
    @Test
    fun `every scalar arm of the mapping table`() {
        assertSchema("""{"type":"string"}""", WireType.Str)
        assertSchema("""{"type":"integer"}""", WireType.Num(integer = true))
        assertSchema("""{"type":"number"}""", WireType.Num(integer = false))
        assertSchema("""{"type":"boolean"}""", WireType.Bool)
        assertSchema("{}", WireType.Any)
        assertSchema("""{"type":"object"}""", WireType.AnyObject)
        assertSchema("""{"type":"array"}""", WireType.AnyArray)
        assertSchema("""{"type":["string","number","boolean"]}""", WireType.Primitive)
    }

    @Test
    fun `a declared class or enum is a component reference, never inlined`() {
        assertSchema("""{"${'$'}ref":"#/components/schemas/CampsiteDto"}""", WireType.Ref("CampsiteDto"))
        assertSchema("""{"${'$'}ref":"#/components/schemas/WatchStatus"}""", WireType.EnumRef("WatchStatus"))
    }

    @Test
    fun `a list carries its element schema in items`() {
        assertSchema(
            """{"type":"array","items":{"${'$'}ref":"#/components/schemas/CampsiteDto"}}""",
            WireType.ArrayOf(WireType.Ref("CampsiteDto")),
        )
        assertSchema("""{"type":"array","items":{"type":"string"}}""", WireType.ArrayOf(WireType.Str))
    }

    @Test
    fun `a map carries its value schema in additionalProperties`() {
        assertSchema(
            """{"type":"object","additionalProperties":{"${'$'}ref":"#/components/schemas/CampsiteDto"}}""",
            WireType.MapOf(WireType.Str, WireType.Ref("CampsiteDto")),
        )
    }

    /**
     * `JsonSchema` has no `propertyNames`, so the key union an enum-keyed map
     * guarantees is not expressible in this model. The value schema is; the key
     * constraint is documented in `docs/backend-architecture.md` instead. This
     * test pins that fact so a Ktor upgrade that adds the field is noticed.
     */
    @Test
    fun `an enum-keyed map constrains only its values`() {
        assertSchema(
            """{"type":"object","additionalProperties":{"type":"string"}}""",
            WireType.MapOf(WireType.EnumRef("FixtureFlavour"), WireType.Str),
        )
    }

    @Test
    fun `an interface declares its properties, its required set and no extras`() {
        assertEquals(
            parsed(
                """
                {
                  "type": "object",
                  "title": "FixtureScalars",
                  "required": ["text", "letter", "count", "big", "ratio", "flag"],
                  "properties": {
                    "text": {"type": "string"},
                    "letter": {"type": "string"},
                    "count": {"type": "integer"},
                    "big": {"type": "integer"},
                    "ratio": {"type": "number"},
                    "flag": {"type": "boolean"}
                  },
                  "additionalProperties": false
                }
                """.trimIndent(),
            ),
            encoded(schemaFor(FixtureScalars::class).getValue("FixtureScalars")),
        )
    }

    @Test
    fun `required in a response body is the complement of nullability alone`() {
        val schema = schemaFor(FixtureOptionality::class).getValue("FixtureOptionality")
        assertEquals(listOf("required", "defaulted"), schema.required)
    }

    @Test
    fun `required in a request body also drops the defaulted fields`() {
        val walk =
            walkContract(
                listOf(
                    ApiEndpoint(
                        ApiMethod.POST,
                        "/api/fixture/FixtureOptionality",
                        request = FixtureOptionality::class,
                        success = ApiBody(HTTP_NO_CONTENT),
                        errors = emptyList(),
                    ),
                ),
            )
        assertEquals(listOf("required"), schemasOf(walk).getValue("FixtureOptionality").required)
    }

    @Test
    fun `an enum is a string with its serial names`() {
        assertEquals(
            parsed("""{"type":"string","title":"FixtureFlavour","enum":["sweet","sour"]}"""),
            encoded(schemaFor(FixtureShapes::class).getValue("FixtureFlavour")),
        )
    }

    @Test
    fun `an optional field is absent from required, not typed nullable`() {
        val schema = schemaFor(FixtureNested::class).getValue("FixtureNested")
        assertEquals(null, schema.required, "every field of FixtureNested is nullable, so nothing is required")
        assertEquals(
            parsed("""{"${'$'}ref":"#/components/schemas/FixtureScalars"}"""),
            encoded(schema.properties!!.getValue("inner")),
        )
    }

    @Test
    fun `the real contract renders a schema for every name the TypeScript declares`() {
        val walk = walkContract()
        assertEquals(
            (walk.interfaces.map { it.name } + walk.enums.map { it.name }).toSortedSet(),
            contractSchemas().keys.toSortedSet(),
        )
        assertTrue(contractSchemas().size > MIN_EXPECTED_SCHEMAS, "expected the contract to declare many schemas")
    }

    @Test
    fun `no schema in the real contract claims a null type`() {
        val document = encodeApiJson(contractSchemas())
        assertTrue(
            !document.contains("\"null\""),
            "the one encoder never writes a null, so no schema may claim the type: $document",
        )
    }

    @Test
    fun `the schema map is ordered by name, so the document is deterministic`() {
        val names = contractSchemas().keys.toList()
        assertEquals(names.sorted(), names)
    }

    private fun schemaFor(kClass: KClass<*>): Map<String, JsonSchema> =
        schemasOf(
            walkContract(
                listOf(
                    ApiEndpoint(
                        ApiMethod.GET,
                        "/api/fixture/${kClass.simpleName}",
                        success = ApiBody(HTTP_OK, kClass),
                        errors = emptyList(),
                    ),
                ),
            ),
        )

    private fun assertSchema(
        expected: String,
        type: WireType,
    ) = assertEquals(parsed(expected), Json.parseToJsonElement(encodeApiJson(jsonSchemaOf(type))), expected)

    private fun encoded(value: JsonSchema): JsonElement = Json.parseToJsonElement(encodeApiJson(value))

    private fun encoded(value: io.ktor.openapi.ReferenceOr<JsonSchema>): JsonElement =
        Json.parseToJsonElement(encodeApiJson(value))

    private fun parsed(json: String): JsonElement = Json.parseToJsonElement(json).jsonObject

    private companion object {
        /** Well under the real count; a floor, so the check means "the walk found the contract". */
        const val MIN_EXPECTED_SCHEMAS = 60
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.apigen.JsonSchemaRenderTest' --offline -q`
Expected: FAIL to compile — `Unresolved reference: jsonSchemaOf`, `schemasOf`, `contractSchemas`.

- [ ] **Step 3: Write the renderer**

Create `backend/src/main/kotlin/ca/floo/roadtrip/apigen/JsonSchemaRender.kt`:

```kotlin
package ca.floo.roadtrip.apigen

import ca.floo.roadtrip.model.api.ApiContract
import ca.floo.roadtrip.model.api.ApiEndpoint
import io.ktor.openapi.AdditionalProperties
import io.ktor.openapi.GenericElementString
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import io.ktor.openapi.ReferenceOr

/** What a `JsonPrimitive` can be on the wire; `SchemaType.AnyOf` renders it as a type list. */
private val jsonPrimitiveTypes = listOf(JsonType.STRING, JsonType.NUMBER, JsonType.BOOLEAN)

/**
 * A generated interface accepts only the keys it declares.
 *
 * Exactly true of a response: the one encoder writes the declared elements and
 * nothing else. For a request it is the contract's *statement* rather than the
 * decoder's behaviour — `roadtripApiJson` has `ignoreUnknownKeys = true`, so an
 * extra key is tolerated at runtime and promised nothing.
 */
private val noExtraProperties: AdditionalProperties = AdditionalProperties.Allowed(false)

/**
 * One [WireType] as a JSON Schema, or as a `$ref` to one.
 *
 * A declared class or enum is always a reference, never inlined, so
 * `components/schemas/CampsiteDto` and `interface CampsiteDto` stay one
 * declaration rendered twice.
 *
 * No arm ever emits `type: null`. The encoder never writes a Kotlin null, and
 * the four `Json*` arms — which *can* carry a `JsonNull` inside them — are
 * deliberately loose rather than nullable: `Any` is the empty schema, and
 * `AnyObject`/`AnyArray`/`Primitive` constrain only the outer shape.
 */
internal fun jsonSchemaOf(type: WireType): ReferenceOr<JsonSchema> =
    when (type) {
        is WireType.Ref -> ReferenceOr.schema(type.name)
        is WireType.EnumRef -> ReferenceOr.schema(type.name)
        WireType.Str -> ReferenceOr.value(JsonSchema(type = JsonType.STRING))
        is WireType.Num ->
            ReferenceOr.value(JsonSchema(type = if (type.integer) JsonType.INTEGER else JsonType.NUMBER))
        WireType.Bool -> ReferenceOr.value(JsonSchema(type = JsonType.BOOLEAN))
        WireType.Any -> ReferenceOr.value(JsonSchema())
        WireType.AnyObject -> ReferenceOr.value(JsonSchema(type = JsonType.OBJECT))
        WireType.AnyArray -> ReferenceOr.value(JsonSchema(type = JsonType.ARRAY))
        WireType.Primitive ->
            ReferenceOr.value(JsonSchema(type = JsonSchema.SchemaType.AnyOf(jsonPrimitiveTypes)))
        is WireType.ArrayOf ->
            ReferenceOr.value(JsonSchema(type = JsonType.ARRAY, items = jsonSchemaOf(type.element)))
        // JsonSchema carries no `propertyNames`, so an enum key's union is not
        // expressible here; the value schema is, and the key constraint is
        // documented in docs/backend-architecture.md.
        is WireType.MapOf ->
            ReferenceOr.value(
                JsonSchema(
                    type = JsonType.OBJECT,
                    additionalProperties = AdditionalProperties.PSchema(jsonSchemaOf(type.value)),
                ),
            )
    }

/**
 * Every declaration [walk] found, as a `components/schemas` map keyed by the
 * same claimed name the TypeScript declares, ordered by name so two runs over an
 * unchanged tree serve an identical document.
 */
internal fun schemasOf(walk: ContractWalk): Map<String, JsonSchema> =
    (walk.enums.map { it.name to enumSchema(it) } + walk.interfaces.map { it.name to interfaceSchema(it) })
        .sortedBy { it.first }
        .toMap(LinkedHashMap())

/** `components/schemas` for [endpoints]: one walk, both views. */
internal fun contractSchemas(endpoints: List<ApiEndpoint> = ApiContract.endpoints): Map<String, JsonSchema> =
    schemasOf(walkContract(endpoints))

/**
 * `required` is the complement of the walk's optionality, under whichever rule
 * the walk applied — nullability alone for a response, nullability or a default
 * for a request. An empty set is omitted rather than written as `[]`.
 */
private fun interfaceSchema(declared: TsInterface): JsonSchema =
    JsonSchema(
        type = JsonType.OBJECT,
        title = declared.name,
        required = declared.fields.filterNot { it.optional }.map { it.name }.takeIf { it.isNotEmpty() },
        properties = declared.fields.associate { it.name to jsonSchemaOf(it.type) }.takeIf { it.isNotEmpty() },
        additionalProperties = noExtraProperties,
    )

private fun enumSchema(declared: TsEnum): JsonSchema =
    JsonSchema(
        type = JsonType.STRING,
        title = declared.name,
        enum = declared.values.map { GenericElementString(it) },
    )
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.apigen.JsonSchemaRenderTest' --offline -q`
Expected: PASS. If `MIN_EXPECTED_SCHEMAS` fails, read the actual count off the message and
lower the floor — do not raise it to the exact number, which would make every new DTO a test
edit.

- [ ] **Step 5: Run the backend gate**

Run: `./gradlew :backend:test :backend:koverXmlReport :backend:koverVerify :backend:ktlintCheck :backend:detekt :backend:checkApiTypes --offline -q`
Expected: PASS. `checkApiTypes` must still report the committed file up to date: this task adds
a renderer and touches neither the walk nor the TypeScript.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/apigen/JsonSchemaRender.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/apigen/JsonSchemaRenderTest.kt
git commit -F /dev/stdin <<'MSG'
feat(apigen): the same walk renders JSON Schema as well as TypeScript

JsonSchemaRender turns a WireType into io.ktor.openapi.JsonSchema and a
ContractWalk into a components/schemas map keyed by the claimed names the
TypeScript already declares, so the two files are two views of one walk. A
declared class or enum is always a $ref; an interface carries its title, its
properties, the complement of the walk's optionality as `required`, and
additionalProperties: false; an enum is a string with its serial names.

No arm emits type: null, because the one encoder never writes one. The tests
assert the encoded JSON under roadtripApiJson rather than the model, so Ktor's
own serializers are covered too — including that explicitNulls = false turns a
39-property data class into a two-key schema.

JsonSchema carries no propertyNames, so the key union an enum-keyed map
guarantees is not expressible; the value schema is, and a test pins the gap so a
Ktor upgrade that closes it is noticed.

Refs #754

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---
### Task 4: the document — schemas, request bodies, statuses, and 401/403 from the tree

`/api/docs/openapi.json` gains `components/schemas` and, on every operation the contract
names, a `requestBody` and a `responses` map. The three route files that never call
`describeApi` get a tag and a summary so no operation in the document is bare.

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/apigen/OpenApiContractDocument.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/route/common/RouteInventory.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/route/common/RouteAccessCoverage.kt:32-40`, `backend/src/main/kotlin/ca/floo/roadtrip/route/api/docs/ApiDocsRoutes.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/route/api/route/RouteRoutes.kt:20-30,144`, `backend/src/main/kotlin/ca/floo/roadtrip/route/api/geocode/GeocodeRoutes.kt:18-31,54`, `backend/src/main/kotlin/ca/floo/roadtrip/route/api/slack/SlackInteractivityRoutes.kt` (the `post` builder's tail)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/apigen/OpenApiContractDocumentTest.kt` (new), `backend/src/test/kotlin/ca/floo/roadtrip/OpenApiSmokeTest.kt`

**Interfaces:**
- Consumes: `ca.floo.roadtrip.apigen.{walkContract, ContractWalk, TsEndpoint, TsBody, schemasOf, tsName}`; `ca.floo.roadtrip.model.api.{ApiContract, ApiEndpoint, ApiMethod, ApiErrorSchema, HTTP_UNAUTHORIZED, HTTP_FORBIDDEN}`; `ca.floo.roadtrip.model.domain.auth.RouteAccess`; `io.ktor.openapi.{OpenApiDoc, Components, PathItem, Operation, Responses, Response, RequestBody, MediaType, JsonSchema, ReferenceOr}`; `io.ktor.http.{ContentType, HttpStatusCode}`; `io.ktor.server.routing.{RoutingNode, HttpMethodRouteSelector, OpenApiRoutePathFormat, path}`; `ca.floo.roadtrip.route.common.{RouteLeaf, walkMethodLeaves, routeAccessAttributeKey, isContractedPath, encodeApiJson}`.
- Produces:
  - `ca.floo.roadtrip.apigen.OpenApiContractDocument.apply` — `fun apply(doc: OpenApiDoc, endpoints: List<ApiEndpoint> = ApiContract.endpoints, accessOf: (ApiMethod, String) -> RouteAccess?): OpenApiDoc`
  - `ca.floo.roadtrip.route.common.declaredAccessByLeaf` — `internal fun RoutingNode.declaredAccessByLeaf(): Map<RouteLeaf, RouteAccess>`
  - `ca.floo.roadtrip.route.common.reachableAccess` — `internal fun RoutingNode.reachableAccess(): RouteAccess?`

- [ ] **Step 1: Add the access walk**

Append to `backend/src/main/kotlin/ca/floo/roadtrip/route/common/RouteInventory.kt`, and add
`import ca.floo.roadtrip.model.domain.auth.RouteAccess`:

```kotlin
/**
 * The access level in force at each method leaf.
 *
 * The routing tree is the authority on 401 and 403: an [ApiContract] row does
 * not restate what `.access(...)` already declares, so the OpenAPI document
 * builder reads the level from here and adds those two responses itself.
 */
internal fun RoutingNode.declaredAccessByLeaf(): Map<RouteLeaf, RouteAccess> {
    val byLeaf = LinkedHashMap<RouteLeaf, RouteAccess>()
    walkMethodLeaves { node ->
        node.reachableAccess()?.let { level ->
            val method = (node.selector as HttpMethodRouteSelector).method.value
            byLeaf[RouteLeaf(method, node.path(OpenApiRoutePathFormat))] = level
        }
    }
    return byLeaf
}

/**
 * The nearest declared [RouteAccess] at or above this node — a group-level
 * `route("/x") { … }.access(…)` covers its children — or null when nothing on
 * the way to the root declares one.
 */
internal fun RoutingNode.reachableAccess(): RouteAccess? {
    var node: RoutingNode? = this
    while (node != null) {
        node.attributes.getOrNull(routeAccessAttributeKey)?.let { return it }
        node = node.parent
    }
    return null
}
```

and in `backend/src/main/kotlin/ca/floo/roadtrip/route/common/RouteAccessCoverage.kt`, delete
the private `hasReachableAccess` and read the shared walk instead — one climb of the tree, in
one place:

```kotlin
        if (!leaf.isAccessExempt() && leaf.reachableAccess() == null) {
```

- [ ] **Step 2: Write the failing document test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/apigen/OpenApiContractDocumentTest.kt`. It
builds the `PathItem`s by hand, so it can assert the rows `OpenApiSmokeTest`'s slice cannot
mount without dragging a controller graph into a smoke test:

```kotlin
package ca.floo.roadtrip.apigen

import ca.floo.roadtrip.model.api.ApiBody
import ca.floo.roadtrip.model.api.ApiEndpoint
import ca.floo.roadtrip.model.api.ApiErrorSchema
import ca.floo.roadtrip.model.api.ApiMethod
import ca.floo.roadtrip.model.api.BuildInfoDto
import ca.floo.roadtrip.model.api.CheckNowCooldownDto
import ca.floo.roadtrip.model.api.HTTP_CONFLICT
import ca.floo.roadtrip.model.api.HTTP_OK
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.encodeApiJson
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.openapi.Operation
import io.ktor.openapi.PathItem
import io.ktor.openapi.ReferenceOr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WATCHES = "/api/watches"
private const val WATCH_DELETE = "/api/watches/{id}/delete"
private const val FORCE_POLLER = "/api/availability/pollers/{id}/force"
private const val SCHEMA_PREFIX = "#/components/schemas/"

/**
 * The contract pass over a document, against hand-built `PathItem`s.
 *
 * Every test that boots `/api/docs` mounts a *slice* of the routing tree, so the
 * rows whose controllers are expensive to assemble — the watch surface, the
 * poller force — are asserted here instead, and `OpenApiSmokeTest` keeps the
 * whole-document invariants its own slice supports.
 */
class OpenApiContractDocumentTest {
    @Test
    fun `a POST row carries its 201, its request body and the 401 its access level implies`() {
        val doc = applied(WATCHES to PathItem(post = Operation(summary = "Create a watch")))
        val post = operation(doc, WATCHES, "post")
        assertEquals("Create a watch", post["summary"]!!.jsonPrimitive.content, "the tree's summary survives")
        assertEquals(
            "${SCHEMA_PREFIX}AvailabilityWatchCreateRequest",
            ref(post["requestBody"]!!.jsonObject),
        )
        assertEquals(true, post["requestBody"]!!.jsonObject["required"]!!.jsonPrimitive.content.toBoolean())
        val responses = post["responses"]!!.jsonObject
        assertEquals("${SCHEMA_PREFIX}AvailabilityWatchResponse", ref(responses.getValue("201").jsonObject))
        assertEquals("${SCHEMA_PREFIX}ApiErrorSchema", ref(responses.getValue("401").jsonObject))
        assertTrue("200" !in responses.keys, "the success status is 201, so no 200 is published: ${responses.keys}")
    }

    @Test
    fun `a 429 row references the cooldown DTO, not the generic error`() {
        val doc = applied(FORCE_POLLER to PathItem(post = Operation()))
        val responses = operation(doc, FORCE_POLLER, "post")["responses"]!!.jsonObject
        assertEquals("${SCHEMA_PREFIX}CheckNowCooldownDto", ref(responses.getValue("429").jsonObject))
        assertEquals("${SCHEMA_PREFIX}CheckNowResponseDto", ref(responses.getValue("200").jsonObject))
        assertEquals("${SCHEMA_PREFIX}ApiErrorSchema", ref(responses.getValue("404").jsonObject))
    }

    @Test
    fun `a 204 is published with no content`() {
        val doc = applied(WATCH_DELETE to PathItem(post = Operation()))
        val noContent = operation(doc, WATCH_DELETE, "post")["responses"]!!.jsonObject.getValue("204").jsonObject
        assertNull(noContent["content"], "a 204 carries no body, so it publishes no content")
        assertEquals("No Content", noContent["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an anonymous row gets no 401 and no 403`() {
        val doc =
            OpenApiContractDocument.apply(
                docWith("/api/health" to PathItem(get = Operation())),
            ) { _, _ -> RouteAccess.Anonymous }
        val responses = operation(doc, "/api/health", "get")["responses"]!!.jsonObject
        assertEquals(setOf("200", "400"), responses.keys)
    }

    @Test
    fun `a role-gated row gets both the 401 and the 403`() {
        val doc =
            OpenApiContractDocument.apply(
                docWith("/api/health" to PathItem(get = Operation())),
            ) { _, _ -> RouteAccess.HasRole(Role.ADMIN) }
        assertEquals(
            setOf("200", "400", "401", "403"),
            operation(doc, "/api/health", "get")["responses"]!!.jsonObject.keys,
        )
    }

    @Test
    fun `a row whose access level already 401s is not published twice`() {
        val doc = applied("/api/watches/{id}" to PathItem(get = Operation()))
        val unauthorized = operation(doc, "/api/watches/{id}", "get")["responses"]!!.jsonObject.getValue("401")
        assertEquals("${SCHEMA_PREFIX}ApiErrorSchema", ref(unauthorized.jsonObject))
        assertNull(unauthorized.jsonObject["content"]!!.jsonObject.values.single().jsonObject["schema"]!!.jsonObject["oneOf"])
    }

    @Test
    fun `two classes at one status become a oneOf`() {
        val row =
            ApiEndpoint(
                ApiMethod.GET,
                "/api/two-bodies",
                success = ApiBody(HTTP_OK, BuildInfoDto::class),
                errors =
                    listOf(
                        ApiBody(HTTP_CONFLICT, ApiErrorSchema::class),
                        ApiBody(HTTP_CONFLICT, CheckNowCooldownDto::class),
                    ),
            )
        val doc =
            OpenApiContractDocument.apply(
                docWith("/api/two-bodies" to PathItem(get = Operation())),
                listOf(row),
            ) { _, _ -> RouteAccess.Anonymous }
        val conflict = operation(doc, "/api/two-bodies", "get")["responses"]!!.jsonObject.getValue("409")
        val schema = conflict.jsonObject["content"]!!.jsonObject.values.single().jsonObject["schema"]!!.jsonObject
        assertEquals(
            listOf("${SCHEMA_PREFIX}ApiErrorSchema", "${SCHEMA_PREFIX}CheckNowCooldownDto"),
            schema["oneOf"]!!.jsonArray.map { it.jsonObject.getValue("\$ref").jsonPrimitive.content },
        )
    }

    @Test
    fun `an unmounted row is skipped, because the boot guard is what requires it`() {
        val doc = applied(WATCHES to PathItem(post = Operation()))
        assertEquals(setOf(WATCHES), doc.paths.keys, "no path is invented for the other 45 rows")
    }

    @Test
    fun `a path the contract does not name is left exactly as the tree described it`() {
        val doc = applied("/test/echo" to PathItem(get = Operation(summary = "echo")))
        val untouched = operation(doc, "/test/echo", "get")
        assertEquals("echo", untouched["summary"]!!.jsonPrimitive.content)
        assertNull(untouched["responses"], "an uncontracted operation gets no responses")
        assertNull(untouched["requestBody"])
    }

    @Test
    fun `every schema the contract declares is in components, and every ref resolves`() {
        val doc = applied(WATCHES to PathItem(post = Operation()))
        val encoded = Json.parseToJsonElement(encodeApiJson(doc)).jsonObject
        val declared = encoded["components"]!!.jsonObject["schemas"]!!.jsonObject.keys
        assertEquals(contractSchemas().keys, declared)
        val unresolved =
            refsIn(encoded).map { it.removePrefix(SCHEMA_PREFIX) }.filterNot { it in declared }.distinct().sorted()
        assertEquals(emptyList(), unresolved)
    }

    private fun applied(vararg items: Pair<String, PathItem>): OpenApiDoc =
        OpenApiContractDocument.apply(docWith(*items)) { _, path ->
            // Mirrors the levels the real tree declares for the rows under test.
            when {
                path.startsWith("/api/watches/") -> RouteAccess.UserOrCapability
                path.startsWith("/api/watches") -> RouteAccess.User
                else -> RouteAccess.Anonymous
            }
        }

    private fun docWith(vararg items: Pair<String, PathItem>): OpenApiDoc =
        OpenApiDoc(
            info = OpenApiInfo(title = "test", version = "0"),
            paths = items.associate { (path, item) -> path to ReferenceOr.Value(item) },
        )

    private fun operation(
        doc: OpenApiDoc,
        path: String,
        verb: String,
    ): JsonObject =
        Json
            .parseToJsonElement(encodeApiJson(doc))
            .jsonObject["paths"]!!
            .jsonObject
            .getValue(path)
            .jsonObject
            .getValue(verb)
            .jsonObject

    private fun ref(holder: JsonObject): String =
        holder["content"]!!
            .jsonObject
            .values
            .single()
            .jsonObject["schema"]!!
            .jsonObject
            .getValue("\$ref")
            .jsonPrimitive
            .content

    /** Every `$ref` anywhere in the document, however deeply nested. */
    private fun refsIn(element: JsonElement): List<String> =
        when (element) {
            is JsonObject ->
                element.entries.flatMap { (key, value) ->
                    if (key == "\$ref") listOf(value.jsonPrimitive.content) else refsIn(value)
                }
            is JsonArray -> element.flatMap(::refsIn)
            else -> emptyList()
        }
}
```

Every case names its row by path and reads the operation with `getValue`, so a renamed path
fails loudly here rather than silently asserting nothing. `ApiMethod` is imported for the
`/api/two-bodies` fixture row.

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.apigen.OpenApiContractDocumentTest' --offline -q`
Expected: FAIL to compile — `Unresolved reference: OpenApiContractDocument`.

- [ ] **Step 4: Write the document builder**

Create `backend/src/main/kotlin/ca/floo/roadtrip/apigen/OpenApiContractDocument.kt`:

```kotlin
package ca.floo.roadtrip.apigen

import ca.floo.roadtrip.model.api.ApiContract
import ca.floo.roadtrip.model.api.ApiEndpoint
import ca.floo.roadtrip.model.api.ApiErrorSchema
import ca.floo.roadtrip.model.api.ApiMethod
import ca.floo.roadtrip.model.api.HTTP_FORBIDDEN
import ca.floo.roadtrip.model.api.HTTP_UNAUTHORIZED
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Components
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.MediaType
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.Operation
import io.ktor.openapi.PathItem
import io.ktor.openapi.ReferenceOr
import io.ktor.openapi.RequestBody
import io.ktor.openapi.Response
import io.ktor.openapi.Responses

private const val REQUEST_BODY_DESCRIPTION = "The body this endpoint decodes, as the DTO the contract names."

/**
 * Where one verb's operation lives on a [PathItem]. Ktor names each verb as its
 * own nullable property, so the dispatch is a registry rather than two parallel
 * `when` blocks that could disagree about which property a verb reads and writes.
 */
private class OperationSlot(
    val read: (PathItem) -> Operation?,
    val write: (PathItem, Operation) -> PathItem,
)

private val operationSlots: Map<ApiMethod, OperationSlot> =
    mapOf(
        ApiMethod.GET to OperationSlot({ it.get }, { item, op -> item.copy(get = op) }),
        ApiMethod.POST to OperationSlot({ it.post }, { item, op -> item.copy(post = op) }),
        ApiMethod.PUT to OperationSlot({ it.put }, { item, op -> item.copy(put = op) }),
        ApiMethod.PATCH to OperationSlot({ it.patch }, { item, op -> item.copy(patch = op) }),
        ApiMethod.DELETE to OperationSlot({ it.delete }, { item, op -> item.copy(delete = op) }),
        ApiMethod.HEAD to OperationSlot({ it.head }, { item, op -> item.copy(head = op) }),
        ApiMethod.OPTIONS to OperationSlot({ it.options }, { item, op -> item.copy(options = op) }),
    )

/**
 * The contract's half of `/api/docs/openapi.json`.
 *
 * The routing tree contributes the paths, the tags and the summaries; this adds
 * `components/schemas` from one walk of the whole contract, each named
 * operation's `requestBody`, and every status it can answer — plus the 401 and
 * 403 the route's own [RouteAccess] implies, which no row restates.
 */
internal object OpenApiContractDocument {
    /**
     * The claimed name of [ApiErrorSchema], the body every access-derived 401 and
     * 403 carries. [tsName] is the same function the walk's `claimName` applies,
     * and the smoke test's "every `$ref` resolves" case is what proves the two
     * agree.
     */
    private val apiErrorSchemaName = tsName(requireNotNull(ApiErrorSchema::class.qualifiedName))

    /**
     * One walk per process for the real contract. Every request for the document
     * would otherwise re-walk ~90 descriptor graphs.
     */
    private val defaultRendering by lazy { renderingFor(ApiContract.endpoints) }

    fun apply(
        doc: OpenApiDoc,
        endpoints: List<ApiEndpoint> = ApiContract.endpoints,
        accessOf: (ApiMethod, String) -> RouteAccess?,
    ): OpenApiDoc {
        val rendering = if (endpoints == ApiContract.endpoints) defaultRendering else renderingFor(endpoints)
        val paths = LinkedHashMap(doc.paths)
        endpoints.zip(rendering.rows).forEach { (row, names) ->
            // A row whose operation is not mounted is skipped: every test mounts a
            // slice of the tree, and the boot guard in registerKoinRoutes is the
            // authority on a non-conditional row with no route.
            val item = paths[row.path]?.valueOrNull() ?: return@forEach
            val slot = operationSlots.getValue(row.method)
            val operation = slot.read(item) ?: return@forEach
            val described = operation.withContract(names, accessOf(row.method, row.path))
            paths[row.path] = ReferenceOr.Value(slot.write(item, described))
        }
        return doc.copy(
            paths = paths,
            components =
                doc.components?.copy(schemas = rendering.schemas) ?: Components(schemas = rendering.schemas),
        )
    }

    private fun renderingFor(endpoints: List<ApiEndpoint>): Rendering =
        walkContract(endpoints).let { Rendering(rows = it.rows, schemas = schemasOf(it)) }

    private fun Operation.withContract(
        names: TsEndpoint,
        access: RouteAccess?,
    ): Operation =
        copy(
            requestBody = names.request?.let(::requestBodyFor) ?: requestBody,
            responses = responsesFor(names, access),
        )

    private fun requestBodyFor(schemaName: String): ReferenceOr<RequestBody> =
        ReferenceOr.value(
            RequestBody(
                description = REQUEST_BODY_DESCRIPTION,
                content =
                    mapOf(
                        ContentType.Application.Json to MediaType(schema = ReferenceOr.schema(schemaName)),
                    ),
                required = true,
            ),
        )

    /**
     * Every status this endpoint can answer, in ascending order. A status the row
     * and the access level both name appears once: the entries are deduplicated
     * by (status, schema), so the tree's 401 never doubles a declared one.
     */
    private fun responsesFor(
        names: TsEndpoint,
        access: RouteAccess?,
    ): Responses =
        Responses(
            responses =
                (listOf(names.success) + names.errors + accessResponses(access))
                    .distinct()
                    .groupBy({ it.status }, { it.type })
                    .toSortedMap()
                    .mapValues { (status, schemas) -> ReferenceOr.Value(responseFor(status, schemas)) },
        )

    /**
     * What the routing tree knows and a row must not restate. `RouteAccess.User`
     * can only ever answer 401 — `check` returns `Forbidden` for a role alone —
     * so it publishes one status, and `HasRole` publishes both. `Anonymous`,
     * `Signed` and `UserOrCapability` refuse nobody at this layer, so they add
     * nothing and whatever their handlers answer stays on the row.
     */
    private fun accessResponses(access: RouteAccess?): List<TsBody> =
        when (access) {
            RouteAccess.User -> listOf(TsBody(HTTP_UNAUTHORIZED, apiErrorSchemaName))
            is RouteAccess.HasRole ->
                listOf(
                    TsBody(HTTP_UNAUTHORIZED, apiErrorSchemaName),
                    TsBody(HTTP_FORBIDDEN, apiErrorSchemaName),
                )
            else -> emptyList()
        }

    private fun responseFor(
        status: Int,
        schemas: List<String?>,
    ): Response {
        val description = HttpStatusCode.fromValue(status).description
        val refs = schemas.filterNotNull().distinct().map { ReferenceOr.schema(it) }
        if (refs.isEmpty()) return Response(description = description)
        val schema: ReferenceOr<JsonSchema> = refs.singleOrNull() ?: ReferenceOr.value(JsonSchema(oneOf = refs))
        return Response(
            description = description,
            content = mapOf(ContentType.Application.Json to MediaType(schema = schema)),
        )
    }

    /** One walk's two products, cached together so the names and the schemas can never be from different walks. */
    private class Rendering(
        val rows: List<TsEndpoint>,
        val schemas: Map<String, JsonSchema>,
    )
}
```

- [ ] **Step 5: Hand the document to the contract**

Rewrite `backend/src/main/kotlin/ca/floo/roadtrip/route/api/docs/ApiDocsRoutes.kt`'s body.
Add `import ca.floo.roadtrip.apigen.OpenApiContractDocument`,
`import ca.floo.roadtrip.model.api.ApiMethod`,
`import ca.floo.roadtrip.model.domain.auth.RouteAccess` (already there),
`import ca.floo.roadtrip.route.common.RouteLeaf`,
`import ca.floo.roadtrip.route.common.declaredAccessByLeaf`,
`import ca.floo.roadtrip.route.common.encodeApiJson`,
`import ca.floo.roadtrip.route.common.isContractedPath`, and
`import java.util.concurrent.atomic.AtomicReference`; drop the now-unused
`import ca.floo.roadtrip.route.common.API_PREFIX` and
`import ca.floo.roadtrip.route.common.underPrefix`:

```kotlin
internal fun Route.apiDocsRoutes() {
    swaggerUI(SWAGGER_UI_PATH) {
        info = roadtripOpenApiInfo
        source = roadtripOpenApiSource()
    }

    route(SWAGGER_UI_PATH) {
        get("/openapi.json") {
            call.respondEncodedJson(call.application.roadtripOpenApiDoc())
        }.hide().access(RouteAccess.Anonymous)
    }
}

/**
 * The document, both times it is served: the routing tree contributes the paths,
 * the tags and the summaries, and `ApiContract` contributes `components/schemas`,
 * each operation's request body and every status it can answer.
 */
private fun Application.roadtripOpenApiDoc(): OpenApiDoc {
    val fromTree = OpenApiDoc(info = roadtripOpenApiInfo) + roadtripOpenApiRoutes()
    val access = contractRouteAccess()
    return OpenApiContractDocument.apply(fromTree) { method, path -> access(method, path) }
}

/**
 * The Swagger UI's own copy of the spec, through the same contract pass.
 *
 * `OpenApiDocSource` is sealed, so the pass has to happen inside `Routing`'s two
 * hooks — and Ktor hands the application to `routes` and not to `serializeModel`,
 * which is why the access lookup is captured between them. `swaggerUI` awaits a
 * `LAZY` async, so `read` runs exactly once per application and the two hooks run
 * in that order, once.
 */
private fun roadtripOpenApiSource(): OpenApiDocSource {
    val access = AtomicReference<(ApiMethod, String) -> RouteAccess?>(null)
    return OpenApiDocSource.Routing(
        contentType = ContentType.Application.Json,
        routes = {
            access.set(contractRouteAccess())
            roadtripOpenApiRoutes()
        },
        serializeModel = { doc ->
            encodeApiJson(
                OpenApiContractDocument.apply(doc) { method, path -> access.get()?.invoke(method, path) },
            )
        },
    )
}

private fun Application.contractRouteAccess(): (ApiMethod, String) -> RouteAccess? {
    val byLeaf = routingRoot.declaredAccessByLeaf()
    return { method, path -> byLeaf[RouteLeaf(method.wireValue, path)] }
}

private fun Application.roadtripOpenApiRoutes(): Sequence<Route> =
    routingRoot
        .descendants()
        .filter(::includeInRoadtripOpenApi)

/**
 * The contracted surface plus the `/test/` probes.
 *
 * `isContractedPath` is the one spelling `RouteInventory` already owns — under
 * `/api/` or `/auth/password/`, minus the whole Swagger subtree — so the two
 * `/auth/password/` rows reach the document and can carry their schemas.
 */
private fun includeInRoadtripOpenApi(route: Route): Boolean {
    val path = route.path(OpenApiRoutePathFormat)
    return isContractedPath(path) || path.startsWith(TEST_PATH_PREFIX)
}
```

- [ ] **Step 6: Give the three bare route files a tag and a summary**

In `backend/src/main/kotlin/ca/floo/roadtrip/route/api/route/RouteRoutes.kt`, add
`import ca.floo.roadtrip.route.common.describeApi`, replace the `Returns:` block of the file
KDoc — the document now publishes those three statuses and their schemas — and describe the
route:

```kotlin
/**
 * GET /api/route?coords=lng,lat;lng,lat;...
 *
 * Backend proxy for the Mapbox Directions API. The token stays server-side, and
 * the corridor radius is validated against [RouteConfig] rather than trusted.
 */
```

```kotlin
        }.describeApi(
            tag = "route",
            summary = "Driving route through the given waypoints, as a GeoJSON FeatureCollection",
            description =
                "`coords` is `lng,lat;lng,lat[;...]`, 2..${routeConfig.maxWaypoints} points. " +
                    "The single LineString feature carries `distance_m`, `duration_s` and `legs[]` in its " +
                    "properties. `radius_miles` optionally buffers the corridor, " +
                    "${routeConfig.minCorridorRadiusMiles}..${routeConfig.maxCorridorRadiusMiles}.",
        ).access(RouteAccess.Anonymous)
```

In `backend/src/main/kotlin/ca/floo/roadtrip/route/api/geocode/GeocodeRoutes.kt`, add the same
import, drop the "Response shape (also documented for swagger)" paragraph from the file KDoc —
the schema is the response shape now — and keep the `bbox` paragraph, which says something the
schema cannot:

```kotlin
        }.describeApi(
            tag = "geocode",
            summary = "Forward-geocode free text to coordinates (Mapbox proxy; the token stays server-side)",
            description =
                "`q` is the text to match. `autocomplete=0` turns off prefix matching, `proximity=lng,lat` " +
                    "biases toward a point, and `limit` caps the results. A result's `bbox` is " +
                    "`[west, south, east, north]` and is present only where the upstream reports an extent — " +
                    "a country, a region, a district, a place, a park with a footprint — which is what lets a " +
                    "client frame a searched-for region as an area instead of flying to a point inside it.",
        ).access(RouteAccess.Anonymous)
```

In `backend/src/main/kotlin/ca/floo/roadtrip/route/api/slack/SlackInteractivityRoutes.kt`, add
the import and describe the webhook on the `post("/interactivity")` builder, before its
existing `.access(...)`:

```kotlin
            }.describeApi(
                tag = "slack",
                summary = "Slack interactivity webhook: acks a signed block_actions payload and dispatches it",
                description =
                    "Mounted only when a Slack signing secret is configured. The body is Slack's own " +
                        "form encoding, not JSON, and every answer is an empty body Slack reads the status " +
                        "of: 200 acked, 400 unusable payload, 401 signature verification failed. The ack is " +
                        "sent before the handler runs, because Slack wants a reply within three seconds.",
            ).access(RouteAccess.Signed)
```

Read the existing `.access(...)` level off the file rather than trusting this snippet's, and
keep it.

- [ ] **Step 7: Extend the smoke test**

In `backend/src/test/kotlin/ca/floo/roadtrip/OpenApiSmokeTest.kt`, mount two more route
functions in the existing `openapi spec lists representative real routes` case — both need
only the detached `DSLContext` that case already builds — and add one case for the contract
half. New imports: `ca.floo.roadtrip.apigen.contractSchemas`,
`ca.floo.roadtrip.model.api.ApiContract`, `ca.floo.roadtrip.repo.AvailabilityPollerRepo`,
`AvailabilityRepo`, `AvailabilityRunRepo`, `CampsiteRepo`,
`ca.floo.roadtrip.route.api.availability.availabilityDashboardRoutes`,
`ca.floo.roadtrip.route.auth.authRoutes`,
`ca.floo.roadtrip.service.availability.AvailabilityDashboardController`,
`kotlinx.serialization.json.JsonElement`, `kotlinx.serialization.json.JsonObject`,
`java.time.Duration`, and `kotlin.test.assertNull`.

Inside both `routing { … }` blocks, after `poisOnRouteRoutes(...)`:

```kotlin
                    authRoutes(wiring = null)
                    availabilityDashboardRoutes(testDashboardController(ctx))
```

with the helper beside `testPoiService`:

```kotlin
    /**
     * Mounted for its *shape*: the document is built from the routing tree, and
     * these routes never answer here. A detached DSLContext is enough, the same
     * way `RouteCorridorRepo` is built above.
     */
    private fun testDashboardController(ctx: DSLContext): AvailabilityDashboardController =
        AvailabilityDashboardController(
            pollerRepo = AvailabilityPollerRepo(ctx),
            runRepo = AvailabilityRunRepo(ctx),
            availabilityRepo = AvailabilityRepo(ctx),
            campsiteRepo = CampsiteRepo(ctx),
            forcePullCooldown = Duration.ofSeconds(FORCE_POLLER_COOLDOWN_SECONDS),
        )
```

and the cooldown as a constant in the file's companion, or as a private top-level
`private const val FORCE_POLLER_COOLDOWN_SECONDS = 60L`.

Then the new case:

```kotlin
    @Test
    fun `the spec carries the contract's schemas, request bodies and statuses`() =
        testApplication {
            application {
                val ctx = DSL.using(SQLDialect.POSTGRES)
                val poiService = testPoiService(ctx)
                routing {
                    apiDocsRoutes()
                    healthRoutes { ReadinessService.Report(databaseReachable = true) }
                    poiRoutes(poiService)
                    authRoutes(wiring = null)
                    availabilityDashboardRoutes(testDashboardController(ctx))
                }
            }

            val spec = Json.parseToJsonElement(client.get("/api/docs/openapi.json").bodyAsText()).jsonObject
            val schemas = spec["components"]!!.jsonObject["schemas"]!!.jsonObject

            // The schemas come from the whole contract, not from what this slice
            // mounts, so every $ref in a partial document still resolves.
            assertEquals(contractSchemas().keys, schemas.keys)
            listOf("ApiErrorSchema", "CampsiteDto", "CheckNowCooldownDto", "WatchStatus").forEach { name ->
                assertTrue(name in schemas.keys, "$name missing from components/schemas")
            }
            assertEquals(emptyList(), refsIn(spec).filterNot { it.removePrefix(SCHEMA_PREFIX) in schemas.keys })

            val paths = spec["paths"]!!.jsonObject

            // A request body arrives as a $ref to the same declaration the
            // TypeScript names.
            assertEquals(
                "${SCHEMA_PREFIX}PoisRequestSchema",
                schemaRef(paths.getValue("/api/pois").jsonObject.getValue("post").jsonObject["requestBody"]!!),
            )

            // The readiness probe serves the same DTO at both statuses.
            val ready = responsesOf(paths, "/api/health/ready", "get")
            assertEquals("${SCHEMA_PREFIX}ReadinessResponseDto", schemaRef(ready.getValue("200")))
            assertEquals("${SCHEMA_PREFIX}ReadinessResponseDto", schemaRef(ready.getValue("503")))

            assertEquals(setOf("200", "400", "404"), responsesOf(paths, "/api/pois/{id}", "get").keys)

            assertEquals(
                "${SCHEMA_PREFIX}CheckNowCooldownDto",
                schemaRef(responsesOf(paths, "/api/availability/pollers/{id}/force", "post").getValue("429")),
            )

            // /auth/password/** is in the document now, so its two rows carry schemas.
            assertEquals(
                "${SCHEMA_PREFIX}PasswordBeginResponseDto",
                schemaRef(responsesOf(paths, "/auth/password/begin", "post").getValue("200")),
            )
            assertNull(
                responsesOf(paths, "/auth/password/complete", "post").getValue("204").jsonObject["content"],
                "a 204 publishes no content",
            )

            // Every mounted row got its responses; nothing in the contracted
            // surface is left bare.
            val bare =
                ApiContract.endpoints
                    .filter { it.path in paths.keys }
                    .filter { row ->
                        val verb = row.method.wireValue.lowercase()
                        paths.getValue(row.path).jsonObject[verb]?.jsonObject?.get("responses") == null
                    }.map { "${it.method.wireValue} ${it.path}" }
            assertEquals(emptyList(), bare)
        }
```

with three private helpers and the prefix constant in the same file:

```kotlin
private const val SCHEMA_PREFIX = "#/components/schemas/"

private fun responsesOf(
    paths: JsonObject,
    path: String,
    verb: String,
): JsonObject =
    paths
        .getValue(path)
        .jsonObject
        .getValue(verb)
        .jsonObject
        .getValue("responses")
        .jsonObject

/** The one media type's schema `$ref`, from a `requestBody` or a `response`. */
private fun schemaRef(holder: JsonElement): String =
    holder.jsonObject["content"]!!
        .jsonObject
        .values
        .single()
        .jsonObject["schema"]!!
        .jsonObject
        .getValue("\$ref")
        .jsonPrimitive
        .content

/** Every `$ref` anywhere in the document, however deeply nested. */
private fun refsIn(element: JsonElement): List<String> =
    when (element) {
        is JsonObject ->
            element.entries.flatMap { (key, value) ->
                if (key == "\$ref") listOf(value.jsonPrimitive.content) else refsIn(value)
            }
        is kotlinx.serialization.json.JsonArray -> element.flatMap(::refsIn)
        else -> emptyList()
    }
```

The existing summary-and-tag assertions stay exactly as they are: the contract pass copies each
operation and replaces only `requestBody` and `responses`, so the tree's tags and summaries
survive. Should `paths` gain `/auth/login`, `/auth/callback` or `/auth/logout` — it must not,
they are outside both covered prefixes — the `bare` assertion is unaffected, since only rows
the contract names are checked; add an `assertFalse(paths.containsKey("/auth/login"))` beside
the existing negative assertions to pin it.

- [ ] **Step 8: Run the document tests**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.apigen.OpenApiContractDocumentTest' --tests 'ca.floo.roadtrip.OpenApiSmokeTest' --offline -q`
Expected: PASS.

If `Responses`' serializer surprises you — it is a `DoublePropertyMixinSerializer` that
flattens the `Map<Int, …>` into the operation object as string keys alongside `x-` extensions —
the failure will be a missing or differently-keyed `responses` object, and the fix is in how
`responsesFor` builds the map, never in the assertion. The one encoder is
`roadtripApiJson`, whose `explicitNulls = false` is what keeps every unset `JsonSchema`,
`Response` and `Operation` property out of the document.

- [ ] **Step 9: Run the backend gate**

Run: `./gradlew :backend:test :backend:koverXmlReport :backend:koverVerify :backend:ktlintCheck :backend:detekt :backend:checkApiTypes --offline -q`
Expected: PASS. `RouteAccessCoverageTest` must stay green: Step 1 replaced its private climb
with the shared one and changed no behaviour.

- [ ] **Step 10: Look at it**

Run: `make run` in one terminal, then open `http://127.0.0.1:8765/api/docs` and expand
`POST /api/watches`.
Expected: the operation shows a request body and a response schema with named fields, not an
empty box. Then:

Run: `curl -s http://127.0.0.1:8765/api/docs/openapi.json | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d["components"]["schemas"]), "schemas,", len(d["paths"]), "paths")'`
Expected: a three-digit schema count and a path count at or above 46.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/apigen/OpenApiContractDocument.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/route/common/RouteInventory.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/route/common/RouteAccessCoverage.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/route/api/docs/ApiDocsRoutes.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/route/api/route/RouteRoutes.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/route/api/geocode/GeocodeRoutes.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/route/api/slack/SlackInteractivityRoutes.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/apigen/OpenApiContractDocumentTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/OpenApiSmokeTest.kt
git commit -F /dev/stdin <<'MSG'
feat(docs): /api/docs serves the contract's schemas, request bodies and statuses

OpenApiContractDocument takes the document Ktor builds from the routing tree —
paths, tags, summaries — and adds components/schemas from one cached walk of the
whole contract, each named operation's requestBody, and every status its row can
answer. The 401 and 403 come from the route's own RouteAccess, read through
RouteInventory's access walk, and are merged by (status, schema) so they never
double a status the handler declares itself.

A row whose operation is not mounted is skipped rather than fatal: every test
mounts a slice of the tree, and the boot guard is already the authority on a
non-conditional row with no route. Schemas still come from the whole contract, so
every $ref resolves in a partial document.

/auth/password/** joins the document: the include predicate is now
RouteInventory's own isContractedPath, so the two password rows carry schemas
instead of being absent. RouteRoutes, GeocodeRoutes and SlackInteractivityRoutes
gain a tag and a summary, and shed the KDoc paragraphs the schemas now say.

Refs #754

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---
### Task 5: `ContractBodyCheck` — every JSON a route test produces, held to its row

A test-side Ktor plugin that `routeTestApplication` installs. On every JSON response whose
matched route matches a contract row, it picks the class the row names for that status,
strict-decodes the body into it, re-encodes with `roadtripApiJson`, and compares the trees.
Then the seven route tests that bypass the harness move onto it.

**This task is where drift surfaces.** Each failure is a finding: say which row and which
route, then fix the DTO or fix the row. Never widen the decoder, never delete an assertion,
never add a status to a row that the route cannot actually answer.

**Files:**
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/route/ContractBodyCheck.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/route/RouteTestApplication.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/route/AvailabilityDashboardRoutesTest.kt`, `.../AdminIngestRoutesTest.kt`, `.../SlackInteractivityRoutesTest.kt`, `.../HealthRoutesTest.kt`, `.../api/BookingRoutesTest.kt`, `.../api/BuildInfoRoutesTest.kt`, `.../auth/AuthRoutesTest.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/route/ContractBodyCheckTest.kt` (new)

**Interfaces:**
- Consumes: `ca.floo.roadtrip.model.api.{ApiContract, bodyAt}`; `ca.floo.roadtrip.route.common.{RouteLeaf, roadtripApiJson}`; `io.ktor.server.application.{Hook, createApplicationPlugin, ApplicationCallPipeline, ApplicationCall}`; `io.ktor.server.response.ApplicationSendPipeline`; `io.ktor.http.content.{OutgoingContent, TextContent}`; `io.ktor.server.routing.{RoutingPipelineCall, RoutingNode, HttpMethodRouteSelector, OpenApiRoutePathFormat, path}`.
- Produces:
  - `ca.floo.roadtrip.route.CONTRACT_LEDGER_PROPERTY` — `internal const val CONTRACT_LEDGER_PROPERTY = "roadtrip.contractLedger"`
  - `ca.floo.roadtrip.route.ContractBodyMismatch` — `internal class ContractBodyMismatch(message: String) : AssertionError(message)`
  - `ca.floo.roadtrip.route.ContractBodyCheck` — the `ApplicationPlugin` value `routeTestApplication` installs
  - `ca.floo.roadtrip.route.ContractLedger` — `internal object ContractLedger { fun check(leaf: RouteLeaf, status: Int, body: String) }`
  - `ca.floo.roadtrip.route.LEDGER_EXERCISED` / `LEDGER_VIOLATION` / `LEDGER_FIELD_SEPARATOR` — `internal const val`s, read again by Task 6's ledger check

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/route/ContractBodyCheckTest.kt`. It drives
each of the plugin's four steps through a real route, over a real contract row, with a handler
it controls:

```kotlin
package ca.floo.roadtrip.route

import ca.floo.roadtrip.model.api.BuildInfoDto
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.encodeApiJson
import ca.floo.roadtrip.route.common.respondEncodedJson
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val BUILD_INFO = "/api/build-info"

/**
 * The check behind the harness, driven through `/api/build-info` — a row with one
 * status and a three-field DTO, so each failure mode is a one-line change to what
 * the handler sends.
 */
class ContractBodyCheckTest {
    @Test
    fun `a body the row's DTO encodes to passes`() =
        testApplication {
            application {
                routeTestApplication {
                    get(BUILD_INFO) {
                        call.respondEncodedJson(BuildInfoDto(env = "test", sha = "abc", branch = "master"))
                    }.access(RouteAccess.Anonymous)
                }
            }
            assertEquals(HttpStatusCode.OK, client.get(BUILD_INFO).status)
        }

    @Test
    fun `a field the DTO does not declare fails, naming the route and the status`() {
        val failure =
            assertFailsWith<ContractBodyMismatch> {
                testApplication {
                    application {
                        routeTestApplication {
                            get(BUILD_INFO) {
                                call.respondText(
                                    """{"env":"test","sha":"abc","branch":"master","surprise":1}""",
                                    ContentType.Application.Json,
                                )
                            }.access(RouteAccess.Anonymous)
                        }
                    }
                    client.get(BUILD_INFO)
                }
            }
        assertTrue(failure.message!!.contains("GET $BUILD_INFO -> 200"), failure.message!!)
        assertTrue(failure.message!!.contains("strict-decode"), failure.message!!)
    }

    @Test
    fun `a status the row does not declare fails, naming the status`() {
        val failure =
            assertFailsWith<ContractBodyMismatch> {
                testApplication {
                    application {
                        routeTestApplication {
                            get(BUILD_INFO) {
                                call.respondEncodedJson(
                                    BuildInfoDto(env = "t", sha = "s", branch = "b"),
                                    HttpStatusCode.NotFound,
                                )
                            }.access(RouteAccess.Anonymous)
                        }
                    }
                    client.get(BUILD_INFO)
                }
            }
        assertTrue(failure.message!!.contains("-> 404"), failure.message!!)
        assertTrue(failure.message!!.contains("declares no body"), failure.message!!)
    }

    @Test
    fun `a body that decodes but re-encodes differently fails`() {
        val failure =
            assertFailsWith<ContractBodyMismatch> {
                testApplication {
                    application {
                        routeTestApplication {
                            get(BUILD_INFO) {
                                // Decodes fine — every field is known — but the DTO has no
                                // nullable field, so the encoder would never omit `branch`.
                                call.respondText("""{"env":"test","sha":"abc"}""", ContentType.Application.Json)
                            }.access(RouteAccess.Anonymous)
                        }
                    }
                    client.get(BUILD_INFO)
                }
            }
        assertTrue(failure.message!!.contains("re-encoded"), failure.message!!)
    }

    @Test
    fun `a path the contract does not name is ignored`() =
        testApplication {
            application {
                routeTestApplication {
                    get("/test/anything") {
                        call.respondText("""{"whatever":true}""", ContentType.Application.Json)
                    }.access(RouteAccess.Anonymous)
                }
            }
            assertEquals(HttpStatusCode.OK, client.get("/test/anything").status)
        }

    @Test
    fun `a non-JSON body is ignored`() =
        testApplication {
            application {
                routeTestApplication {
                    get(BUILD_INFO) { call.respondText("not json at all") }.access(RouteAccess.Anonymous)
                }
            }
            assertEquals(HttpStatusCode.OK, client.get(BUILD_INFO).status)
        }

    @Test
    fun `the round trip is the one encoder, so a contract DTO always passes its own output`() {
        val dto = BuildInfoDto(env = "test", sha = "abc", branch = "master")
        assertEquals("""{"env":"test","sha":"abc","branch":"master"}""", encodeApiJson(dto))
    }
}
```

The third case is where a missing `branch` is a *re-encode* failure rather than a decode
failure: `BuildInfoDto`'s fields are non-null with no defaults, so kotlinx refuses the decode
and the message says `strict-decode` instead. If that is what the run reports, change the case
to omit a field that *does* have a default, or to send `{"env":"test","sha":"abc","branch":null}`
— a null the encoder would have dropped — and keep the assertion on `re-encoded`. Read
`BuildInfoDto` before writing it.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.ContractBodyCheckTest' --offline -q`
Expected: FAIL to compile — `Unresolved reference: ContractBodyMismatch`.

- [ ] **Step 3: Write the plugin**

Create `backend/src/test/kotlin/ca/floo/roadtrip/route/ContractBodyCheck.kt`:

```kotlin
package ca.floo.roadtrip.route

import ca.floo.roadtrip.model.api.ApiContract
import ca.floo.roadtrip.model.api.bodyAt
import ca.floo.roadtrip.route.common.RouteLeaf
import ca.floo.roadtrip.route.common.roadtripApiJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.withoutParameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.OpenApiRoutePathFormat
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingPipelineCall
import io.ktor.server.routing.path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.createType

/** Where Gradle tells the suite to append its ledger. Absent in an IDE run: the checks still run, nothing is written. */
internal const val CONTRACT_LEDGER_PROPERTY = "roadtrip.contractLedger"

internal const val LEDGER_EXERCISED = "EXERCISED"
internal const val LEDGER_VIOLATION = "VIOLATION"
internal const val LEDGER_FIELD_SEPARATOR = "\t"

/**
 * `roadtripApiJson`'s decode behaviour with one setting tightened.
 *
 * `explicitNulls = false` is not a loosening: it is what lets a body honestly
 * omit a nullable field, which the one encoder always does. `ignoreUnknownKeys =
 * false` is the tightening — at runtime the decoder tolerates an extra key and
 * promises nothing about it, and here an extra key is exactly the drift being
 * hunted.
 */
private val strictApiJson =
    Json {
        explicitNulls = false
        ignoreUnknownKeys = false
    }

/** A response body the contract cannot account for. The message names the route, the status and the difference. */
internal class ContractBodyMismatch(
    message: String,
) : AssertionError(message)

/**
 * After every Transform, before any ContentEncoding.
 *
 * The send pipeline runs Before, Transform, Render, ContentEncoding,
 * TransferEncoding, After, Engine. `onCallRespond` is Transform, where the
 * subject may still be the DTO `ContentNegotiation` is about to serialize; the
 * built-in `ResponseBodyReadyForSend` hook is After, by which point `Compression`
 * may have replaced the body. Render is the one phase that sees the final text,
 * uncompressed — and it sees it for route responses too, because
 * `RoutingRoot.executeResult` merges the application's send pipeline with the
 * route's and executes it with the routing call.
 */
private object ResponseRendered : Hook<suspend (ApplicationCall, OutgoingContent) -> Unit> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (ApplicationCall, OutgoingContent) -> Unit,
    ) {
        pipeline.sendPipeline.intercept(ApplicationSendPipeline.Render) {
            (subject as? OutgoingContent)?.let { handler(call, it) }
        }
    }
}

/**
 * Holds every JSON body a route test produces to the `ApiContract` row for its
 * method, path and status.
 *
 * Installed by [routeTestApplication], so a test gets it by using the harness and
 * nothing else. A row naming the wrong DTO, a DTO that drifted from what the
 * route serves, and a status no row declares each fail the test that produced
 * them.
 */
internal val ContractBodyCheck =
    createApplicationPlugin(name = "ContractBodyCheck") {
        on(ResponseRendered) { call, content ->
            val text = content as? TextContent ?: return@on
            if (text.contentType.withoutParameters() != ContentType.Application.Json) return@on
            val leaf = call.contractLeaf() ?: return@on
            ContractLedger.check(leaf, call.response.status()?.value ?: HttpStatusCode.OK.value, text.text)
        }
    }

/**
 * The `(verb, path template)` of the matched route, spelled the way `ApiContract`
 * spells it. Null for a call that never reached a routing node carrying a verb.
 */
private fun ApplicationCall.contractLeaf(): RouteLeaf? {
    var node: RoutingNode? = (this as? RoutingPipelineCall)?.route
    while (node != null) {
        val selector = node.selector
        if (selector is HttpMethodRouteSelector) {
            return RouteLeaf(selector.method.value, node.path(OpenApiRoutePathFormat))
        }
        node = node.parent
    }
    return null
}

/**
 * The check, and the process-wide record of what the run exercised.
 *
 * Test classes run as threads in one JVM — `junit-platform.properties` sets
 * `classes.default=concurrent` and `maxParallelForks` is the default 1 — so one
 * file under one lock is enough and no per-fork name is needed. Every outcome is
 * written, not just the failures, because a throw inside the send pipeline can in
 * principle be swallowed into a 500 and the ledger is then the record that fails
 * the build.
 */
internal object ContractLedger {
    private val rowsByLeaf = ApiContract.endpoints.associateBy { RouteLeaf(it.method.wireValue, it.path) }
    private val serializers = ConcurrentHashMap<KClass<*>, KSerializer<Any?>>()
    private val ledgerFile = System.getProperty(CONTRACT_LEDGER_PROPERTY)?.let(::File)
    private val lock = Any()

    fun check(
        leaf: RouteLeaf,
        status: Int,
        body: String,
    ) {
        // A response on a path no row names is not this check's business: the row
        // map is the ignore list, which is what covers /test/**, /data/** and the
        // synthetic probe paths the coverage tests mount.
        val row = rowsByLeaf[leaf] ?: return
        val declared =
            row.bodyAt(status)
                ?: fail(
                    leaf,
                    status,
                    "the contract declares no body at $status. Add an ApiBody($status, …) to the row in " +
                        "model/api/ApiContract.kt, or stop serving that status.",
                )
        val expected =
            declared.body
                ?: fail(leaf, status, "the contract declares $status as body-less, but the route sent JSON.")
        compare(leaf, status, expected, body)
        record(LEDGER_EXERCISED, leaf, status, requireNotNull(expected.qualifiedName))
    }

    private fun compare(
        leaf: RouteLeaf,
        status: Int,
        expected: KClass<*>,
        body: String,
    ) {
        val serializer = serializers.getOrPut(expected) { serializer(expected.createType()) }
        val decoded =
            try {
                strictApiJson.decodeFromString(serializer, body)
            } catch (cause: Exception) {
                fail(
                    leaf,
                    status,
                    "the body does not strict-decode into ${expected.qualifiedName}: ${cause.message}. " +
                        "Either the row names the wrong DTO, or the route serves a field the DTO lacks.",
                )
            }
        val sent = Json.parseToJsonElement(body)
        val roundTripped: JsonElement = roadtripApiJson.encodeToJsonElement(serializer, decoded)
        if (roundTripped != sent) {
            fail(
                leaf,
                status,
                "the body is not what ${expected.qualifiedName} encodes to. " +
                    "sent: $sent -- re-encoded: $roundTripped",
            )
        }
    }

    private fun fail(
        leaf: RouteLeaf,
        status: Int,
        detail: String,
    ): Nothing {
        record(LEDGER_VIOLATION, leaf, status, detail)
        throw ContractBodyMismatch("${leaf.method} ${leaf.path} -> $status: $detail")
    }

    private fun record(
        kind: String,
        leaf: RouteLeaf,
        status: Int,
        detail: String,
    ) {
        val file = ledgerFile ?: return
        val fields = listOf(kind, leaf.method, leaf.path, status.toString(), detail.replace('\n', ' '))
        synchronized(lock) {
            file.parentFile?.mkdirs()
            file.appendText(fields.joinToString(LEDGER_FIELD_SEPARATOR) + "\n")
        }
    }
}
```

- [ ] **Step 4: Install it in the harness**

Rewrite `backend/src/test/kotlin/ca/floo/roadtrip/route/RouteTestApplication.kt`:

```kotlin
package ca.floo.roadtrip.route

import ca.floo.roadtrip.installRoadtripPlugins
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing

/**
 * The route-test harness: the plugins a real request goes through, plus the
 * contract body check.
 *
 * [ContractBodyCheck] is installed *after* [installRoadtripPlugins], so its
 * Render-phase hook sees the text `ContentNegotiation` produced and sees it
 * before `Compression` touches it.
 */
internal fun Application.routeTestApplication(body: Route.() -> Unit) {
    installRoadtripPlugins()
    install(ContractBodyCheck)
    routing(body)
}
```

- [ ] **Step 5: Run the plugin's own test**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.ContractBodyCheckTest' --offline -q`
Expected: PASS. If the throw does not reach the client call — Ktor's `StatusPages` rethrows
when no handler matches its type, and `ContractBodyMismatch` is an `AssertionError` nothing
handles — the failure will surface as a 500 instead of an exception; in that case assert on the
ledger instead, and say so in the task report, because Task 6's `contractLedgerCheck` is the
backstop that keeps such a drift from passing the build.

- [ ] **Step 6: Run every test already on the harness**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.*' --tests 'ca.floo.roadtrip.OpenApiSmokeTest' --offline -q`
Expected: PASS, or a `ContractBodyMismatch` naming a row. Nine suites already use
`routeTestApplication` — `AvailabilityWatchRoutesTest`, `CampsiteRoutesTest`,
`GeocodeRoutesTest`, `PoiRoutesTest`, `PoisOnRouteRoutesTest`, `BulkAvailabilityRoutesTest`,
`RecgovSettingsRoutesTest`, `SettingsRoutesTest`, `RouteRequestBodiesTest` — so this run is the
first broad exposure. For each failure: read the message, decide whether the DTO or the row is
wrong, fix that, and record it in the task report. Do not touch `strictApiJson`.

- [ ] **Step 7: Move the seven bypassing tests onto the harness**

In each file, add `import ca.floo.roadtrip.route.routeTestApplication` (or, for the three
already in package `ca.floo.roadtrip.route`, nothing) and replace every
`application { routing { … } }` with `application { routeTestApplication { … } }`, keeping any
`install(roadtripAuthorization) { … }` call ahead of it — the order `SettingsRoutesTest`
already uses. The `import io.ktor.server.routing.routing` goes if nothing else in the file
needs it.

| File | Occurrences | Rows it is the only 2xx cover for |
|---|---|---|
| `route/HealthRoutesTest.kt` | 3 (`:31`, `:43`, `:56`) | `GET /api/health`, `GET /api/health/ready` at 200 **and** 503 |
| `route/AdminIngestRoutesTest.kt` | 8 | the four admin rows |
| `route/AvailabilityDashboardRoutesTest.kt` | 14 | pollers, pollers/summary, runs, force |
| `route/SlackInteractivityRoutesTest.kt` | 5 | none — every answer is empty `text/plain` |
| `route/api/BookingRoutesTest.kt` | 12 | `POST /api/booking/add-to-cart` |
| `route/api/BuildInfoRoutesTest.kt` | 1 | `GET /api/build-info` |
| `route/auth/AuthRoutesTest.kt` | 5 | `GET /api/me` |

`route/ReadinessPoolExhaustionTest.kt` stays on bare `routing { }` (Resolution 15): it measures
what an exhausted Hikari pool does to the readiness probe, `HealthRoutesTest` already produces
both of that row's bodies through the harness, and wrapping a pool-exhaustion test in
`Compression` and `CachingHeaders` changes what it measures for no coverage gain. Add one line
of KDoc on the class saying exactly that, so the next reader does not "fix" it.

- [ ] **Step 8: Run the whole suite and fix what it reveals**

Run: `./gradlew :backend:test --offline -q`
Expected: PASS, or a `ContractBodyMismatch`. The plausible finds, in order of likelihood:
`AdminIngestRoutesTest`'s 500 answers (rows 41 and 42 declare their 2xx DTO again at 500 — if a
run disagrees, read `AdminIngestRoutes.kt:115` and `:186` again); `BookingRoutesTest`'s 403,
409, 422 and 502 (all `ApiErrorSchema`, all on row 16); `AvailabilityDashboardRoutesTest`'s 429
(`CheckNowCooldownDto`, row 32). Each is a finding to report, not a reason to loosen anything.

- [ ] **Step 9: Prove the check actually catches a wrong row**

The spec's verification step 2, as a scratch change you revert:

```bash
# Point row 38 at a structurally different DTO.
sed -i '' 's/ApiBody(HTTP_OK, BuildInfoDto::class)/ApiBody(HTTP_OK, HealthResponseDto::class)/' \
  backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiContract.kt
./gradlew :backend:test --tests 'ca.floo.roadtrip.route.api.BuildInfoRoutesTest' --offline -q
git checkout -- backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiContract.kt
```

Expected: the run fails with `GET /api/build-info -> 200: the body does not strict-decode into
ca.floo.roadtrip.model.api.HealthResponseDto`. Then the `git checkout` restores the row and
the suite is green again. Record the message in the task report.

- [ ] **Step 10: Run the backend gate**

Run: `./gradlew :backend:test :backend:koverXmlReport :backend:koverVerify :backend:ktlintCheck :backend:detekt :backend:checkApiTypes --offline -q`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add backend/src/test/kotlin/ca/floo/roadtrip/route/ContractBodyCheck.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/ContractBodyCheckTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/RouteTestApplication.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/HealthRoutesTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/ReadinessPoolExhaustionTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/AdminIngestRoutesTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/AvailabilityDashboardRoutesTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/SlackInteractivityRoutesTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/api/BookingRoutesTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/api/BuildInfoRoutesTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/auth/AuthRoutesTest.kt
git commit -F /dev/stdin <<'MSG'
test(api): every JSON a route test produces is held to its contract row

ContractBodyCheck is a test-side plugin routeTestApplication installs. On every
JSON response whose matched route matches a row, it picks the class the row names
for that status, strict-decodes the body into it, re-encodes with roadtripApiJson
and compares the two JsonElement trees. A row naming the wrong DTO, a DTO that
drifted from what the route serves, and a status no row declares each fail the
test that produced them. Json* fields compare as trees, so a JsonNull inside one
round-trips honestly.

The hook is the send pipeline's Render phase: onCallRespond is Transform, where
the subject may still be the DTO ContentNegotiation is about to serialize, and
ResponseBodyReadyForSend is After, by which point Compression may have replaced
the body. The strict decoder mirrors the one encoder and tightens exactly one
setting — an unknown key — because explicitNulls = false is what lets a body
honestly omit a nullable field.

Seven suites that called bare routing { } move onto the harness, which is what
brings /api/me, /api/build-info, /api/health, /api/health/ready, the admin
surface, the availability dashboard and add-to-cart under the check.
ReadinessPoolExhaustionTest stays as it was, and now says why.

Refs #754

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---
### Task 6: the six missing 2xx tests, and the ledger that proves there are no more

Six rows have no test that produces their success body, so the check in Task 5 never sees
them. They get one each. Then a ledger makes the gap impossible to reintroduce: the plugin
appends every checked response to a file, and `:backend:contractLedgerCheck` fails when a row
with a success body is not in it.

**Files:**
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/route/ContractLedgerCheck.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/route/api/settings/SettingsRoutesTest.kt`, `.../route/PoiRoutesTest.kt`, `.../route/AvailabilityDashboardRoutesTest.kt`, `.../route/AdminIngestRoutesTest.kt`, `.../route/auth/AuthRoutesTest.kt`, `backend/build.gradle.kts:461-471`, `Makefile:104`, `.github/workflows/ci.yml:410`

**Interfaces:**
- Consumes: `ca.floo.roadtrip.route.{CONTRACT_LEDGER_PROPERTY, LEDGER_EXERCISED, LEDGER_VIOLATION, LEDGER_FIELD_SEPARATOR}` (Task 5); `ca.floo.roadtrip.model.api.ApiContract`.
- Produces: `ca.floo.roadtrip.route.ContractLedgerCheckKt.main` — `fun main(args: Array<String>)`, argv[0] the ledger path, exit 1 on drift; the Gradle task `:backend:contractLedgerCheck`.

- [ ] **Step 1: `PUT /api/settings/notifications` — the happy path**

Add to `backend/src/test/kotlin/ca/floo/roadtrip/route/api/settings/SettingsRoutesTest.kt`,
just above the `PUT notifications error mapping` comment block:

```kotlin
    @Test
    fun `PUT notifications authenticated returns 200 with the updated settings`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routeTestApplication {
                    settingsRoutes(
                        StubSettingsService(
                            onUpdateNotifications = { _, req ->
                                defaultSettingsDto().copy(
                                    notifications =
                                        defaultSettingsDto().notifications.copy(
                                            notificationEmail = req.notificationEmail,
                                        ),
                                )
                            },
                        ),
                    )
                }
            }
            val resp =
                client.put(NOTIFICATIONS_PATH) {
                    userSession()
                    contentType(ContentType.Application.Json)
                    setBody("""{"notification_email": "bob@example.com"}""")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val notifications =
                Json
                    .parseToJsonElement(resp.bodyAsText())
                    .jsonObject["notifications"]!!
                    .jsonObject
            assertEquals("bob@example.com", notifications["notification_email"]!!.jsonPrimitive.content)
        }
```

- [ ] **Step 2: `GET /api/pois/{id}` — the detail feature**

Add to `backend/src/test/kotlin/ca/floo/roadtrip/route/PoiRoutesTest.kt`, after
`accidental poi health route no longer returns ok`:

```kotlin
    @Test
    fun `poi detail returns the wide feature for a seeded row`() =
        testApplication {
            // Seeded directly rather than through `seed`, because this case needs the id.
            val poiId =
                ctx
                    .seedCatalogPoi(
                        sourceId = "detail-1",
                        name = "Upper Pines",
                        lon = -119.56,
                        lat = 37.74,
                        poiType = "campground",
                    ).poiId
            application { routeTestApplication { poiRoutes(poiService()) } }

            val resp = client.get("/api/pois/$poiId")
            assertEquals(HttpStatusCode.OK, resp.status)
            val feature = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("Feature", feature["type"]!!.jsonPrimitive.content)
            assertEquals("Upper Pines", feature["properties"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        }
```

`seedCatalogPoi` defaults `source` to `DataProvider.RECGOV.id`, which is the one provider
`poiService()` enables, and `refresh = true`, so the canonical views see the row.

- [ ] **Step 3: the two availability dashboard reads**

Add to `backend/src/test/kotlin/ca/floo/roadtrip/route/AvailabilityDashboardRoutesTest.kt`,
beside their existing 400 cases:

```kotlin
    @Test
    fun `GET pollers id runs lists that poller's runs`() =
        testApplication {
            application { routeTestApplication { testAvailabilityDashboardRoutes() } }
            val pollerId = seedPoller()
            val runRepo = AvailabilityRunRepo(ctx)
            val runId = runRepo.start(pollerId, OffsetDateTime.now(ZoneOffset.UTC))
            runRepo.complete(runId, snapshotCount = 3, completedAt = OffsetDateTime.now(ZoneOffset.UTC), durationMs = 12)

            val resp = client.get("/api/availability/pollers/$pollerId/runs")
            assertEquals(HttpStatusCode.OK, resp.status)
            val runs = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["runs"]!!.jsonArray
            assertEquals(listOf(runId), runs.map { it.jsonObject["id"]!!.jsonPrimitive.long })
        }

    @Test
    fun `GET changes filtered by poi id lists the recorded change rows`() =
        testApplication {
            application { routeTestApplication { testAvailabilityDashboardRoutes() } }
            val fixture = seedSummaryFixture()
            val observedAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)
            recordObservation(fixture.campsiteId, "2026-07-04", observedAt, available = true)
            recordObservation(fixture.campsiteId, "2026-07-04", observedAt.plusMinutes(10), available = false)

            val resp = client.get("/api/availability/changes?poi_id=${fixture.poiId}")
            assertEquals(HttpStatusCode.OK, resp.status)
            val changes = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["changes"]!!.jsonArray
            assertEquals(
                true,
                changes.isNotEmpty(),
                "two observations of one campsite-date produce at least one change row",
            )
        }
```

The remaining twelve occurrences of `application { routing { … } }` in this file switched to
`routeTestApplication` in Task 5; these two are written on the harness from the start.

- [ ] **Step 4: `GET /api/admin/data/runs/{id}` — one run with its phases**

Add to `backend/src/test/kotlin/ca/floo/roadtrip/route/AdminIngestRoutesTest.kt`, after
`GET runs filters by target`:

```kotlin
    @Test
    fun `GET one run returns its detail with an empty phase list`() =
        testApplication {
            val controller = controllerWith(mapOf("alpha" to Target("alpha", emptyList())))
            application { routeTestApplication { adminIngestRoutes(controller) } }

            assertEquals(HttpStatusCode.OK, client.post("/api/admin/data/import/alpha").status)
            val listed =
                Json
                    .parseToJsonElement(client.get("/api/admin/data/runs").bodyAsText())
                    .jsonObject["runs"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            val runId = listed["id"]!!.jsonPrimitive.long

            val resp = client.get("/api/admin/data/runs/$runId")
            assertEquals(HttpStatusCode.OK, resp.status)
            val detail = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(runId, detail["id"]!!.jsonPrimitive.long)
            assertEquals("alpha", detail["target"]!!.jsonPrimitive.content)
            assertEquals(0, detail["phases"]!!.jsonArray.size, "a target with no import phases runs none")
        }
```

adding `import kotlinx.serialization.json.long` to the file.

- [ ] **Step 5: `POST /auth/password/begin` — the stubbed wiring**

Add to `backend/src/test/kotlin/ca/floo/roadtrip/route/auth/AuthRoutesTest.kt`, after the
connection-allowlist cases. `authOnWiring()`'s `EchoingIdentityProvider` already answers
`authorizationRequest` with no network, and `AuthController.beginPasswordLogin` does nothing
else but derive the PKCE challenge from it:

```kotlin
    @Test
    fun `POST password begin returns the flow the frontend needs`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = { Principal.Anonymous } }
                routeTestApplication { authRoutes(wiring = authOnWiring()) }
            }
            val resp =
                client.post("/auth/password/begin") {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("state", body["state"]!!.jsonPrimitive.content)
            assertEquals("nonce", body["nonce"]!!.jsonPrimitive.content)
            assertEquals(
                "https://test.example/auth/callback",
                body["redirect_uri"]!!.jsonPrimitive.content,
                "the frontend must send auth0-js the same redirect_uri the backend exchanges with",
            )
            assertEquals(
                true,
                body["code_challenge"]!!.jsonPrimitive.content.isNotBlank(),
                "the PKCE challenge is derived from the verifier the flow cookie carries",
            )
        }
```

adding `import io.ktor.client.request.post`, `import io.ktor.client.request.setBody`,
`import io.ktor.http.ContentType`, `import io.ktor.http.contentType` and
`import ca.floo.roadtrip.route.routeTestApplication` to the file.

- [ ] **Step 6: Run the six**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.api.settings.SettingsRoutesTest' --tests 'ca.floo.roadtrip.route.PoiRoutesTest' --tests 'ca.floo.roadtrip.route.AvailabilityDashboardRoutesTest' --tests 'ca.floo.roadtrip.route.AdminIngestRoutesTest' --tests 'ca.floo.roadtrip.route.auth.AuthRoutesTest' --offline -q`
Expected: PASS — and each new case now also runs the body check on a success body nothing
exercised before, so a `ContractBodyMismatch` here is a finding about the row, not the test.

- [ ] **Step 7: Write the ledger check**

Create `backend/src/test/kotlin/ca/floo/roadtrip/route/ContractLedgerCheck.kt`:

```kotlin
package ca.floo.roadtrip.route

import ca.floo.roadtrip.model.api.ApiContract
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = "usage: ContractLedgerCheck <path/to/exercised.tsv>"
private const val DRIFT_EXIT_CODE = 1
private const val KIND_FIELD = 0
private const val METHOD_FIELD = 1
private const val PATH_FIELD = 2
private const val STATUS_FIELD = 3

/**
 * Every contract row with a success body was produced by some route test.
 *
 * `ContractBodyCheck` holds each body a test produces to its row; this holds the
 * *suite* to the contract, so a row nothing exercises cannot quietly stop being
 * covered. Any `VIOLATION` line fails too: a throw inside the send pipeline can
 * in principle be swallowed into a 500, and the ledger is then the record that
 * fails the build.
 *
 * A `JavaExec` rather than a second `Test` task deliberately — a second `Test`
 * task joins Kover's instrumented set and changes what `koverVerify` measures —
 * and an argv-and-exit verifier in the same shape as `apigen/GenerateApiTypes`.
 */
fun main(args: Array<String>) {
    val ledger = File(args.firstOrNull() ?: error(USAGE))
    if (!ledger.exists()) {
        System.err.println(
            "${ledger.path} is missing. :backend:contractLedgerCheck must run after :backend:test, " +
                "which is what writes it.",
        )
        exitProcess(DRIFT_EXIT_CODE)
    }
    val lines = ledger.readLines().filter { it.isNotBlank() }.map { it.split(LEDGER_FIELD_SEPARATOR) }
    val violations = lines.filter { it[KIND_FIELD] == LEDGER_VIOLATION }
    val exercised =
        lines
            .filter { it[KIND_FIELD] == LEDGER_EXERCISED }
            .mapTo(HashSet()) { Triple(it[METHOD_FIELD], it[PATH_FIELD], it[STATUS_FIELD]) }
    val unexercised =
        ApiContract.endpoints
            .filter { it.success.body != null }
            .filterNot {
                Triple(it.method.wireValue, it.path, it.success.status.toString()) in exercised
            }.map { "${it.method.wireValue} ${it.path} (${it.success.status})" }
            .sorted()

    if (violations.isEmpty() && unexercised.isEmpty()) {
        println("${exercised.size} contracted responses exercised; every row with a success body is covered")
        return
    }
    violations.forEach { System.err.println("contract body drift: ${it.joinToString(" ")}") }
    if (unexercised.isNotEmpty()) {
        System.err.println(
            "no route test produced the success body of:\n  ${unexercised.joinToString("\n  ")}\n" +
                "Add a 2xx route test on routeTestApplication for each, or drop the row.",
        )
    }
    exitProcess(DRIFT_EXIT_CODE)
}
```

- [ ] **Step 8: Wire it into Gradle**

In `backend/build.gradle.kts`, beside the `generateApiTypes` / `checkApiTypes` block, add the
ledger path; then extend `tasks.test` and register the task:

```kotlin
// The contract exercise ledger. ContractBodyCheck appends one line per checked
// response; contractLedgerCheck reads the file back and fails when a row with a
// success body was never produced. A JavaExec rather than a second Test task, so
// Kover's instrumented set — and therefore koverVerify's number — does not change.
val contractLedgerFile = layout.buildDirectory.file("contract-ledger/exercised.tsv")
val contractLedgerProperty = "roadtrip.contractLedger"
```

```kotlin
tasks.test {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
    // Headroom for the parallel test workers (classes run concurrently in one
    // JVM — see src/test/resources/junit-platform.properties).
    maxHeapSize = "4g"
    systemProperty(contractLedgerProperty, contractLedgerFile.get().asFile.absolutePath)
    // One JVM, so one file; cleared here rather than by the plugin, which has no
    // notion of when a run begins.
    doFirst { delete(contractLedgerFile) }
    finalizedBy("contractLedgerCheck")
}

tasks.register<JavaExec>("contractLedgerCheck") {
    group = "verification"
    description = "Fail when a contract row's success body was never produced by a route test."
    mainClass.set("ca.floo.roadtrip.route.ContractLedgerCheckKt")
    classpath = sourceSets["test"].runtimeClasspath
    args(contractLedgerFile.get().asFile.absolutePath)
    mustRunAfter(tasks.test)
    // A failed or skipped :backend:test leaves an incomplete ledger; the test
    // failure is the message that matters, so do not add a second one.
    onlyIf { tasks.test.get().state.didWork && tasks.test.get().state.failure == null }
    outputs.upToDateWhen { false }
}

tasks.named("check") { dependsOn("contractLedgerCheck") }
```

`finalizedBy` is what makes the bare `:backend:test` in `make test` and in CI gate on it; the
`check` dependency is for `./gradlew check`. `outputs.upToDateWhen { false }` because the task
has no declared inputs and must run on every suite.

- [ ] **Step 9: Run it and watch it name what is missing**

Run: `./gradlew :backend:test --offline -q`
Expected: PASS, and a final line from `contractLedgerCheck` reporting the exercised count. If
it instead lists unexercised rows, those are rows Steps 1-5 did not cover — read the list, add
a 2xx test for each in the suite that already owns its route file, following the nearest
existing case in that file for its fixtures, and re-run. Rows whose `success.body` is null
(`/auth/password/complete`, `/api/watches/{id}/delete`, `/api/slack/interactivity`) are
excluded by the check and need nothing.

Then prove the gate bites:

```bash
./gradlew :backend:test --tests 'ca.floo.roadtrip.route.api.BuildInfoRoutesTest' --offline -q
```

Expected: FAIL from `contractLedgerCheck`, listing the other 42 rows with a success body,
because a filtered run exercises only one. That is the gate working; the full-suite run above
is the one that must be green.

- [ ] **Step 10: Add it to both gates**

In `Makefile`, extend the `test` target's gradle line and its comment:

```make
# checkApiTypes and contractLedgerCheck both run over the backend's own classpath,
# so they need the same compile (and therefore the same Docker) :backend:test
# already needs. ci.yml runs them in backend-tests, never in gradle-lint.
test: _ensure-hooks
	./gradlew :backend:test :backend:koverXmlReport :backend:koverVerify :backend:ktlintCheck :backend:detekt :backend:checkApiTypes :backend:contractLedgerCheck :detekt-rules:test
```

In `.github/workflows/ci.yml`, the `backend-tests` job's run line:

```yaml
      - name: Run backend tests + coverage + API contract check
        run: ./gradlew :backend:test :backend:koverXmlReport :backend:koverVerify :backend:checkApiTypes :backend:contractLedgerCheck
```

Both are redundant with `finalizedBy` and named on purpose: a reader of the `Makefile` or the
workflow can see every gate without reading `build.gradle.kts`.

- [ ] **Step 11: Run both gates**

Run: `./gradlew :backend:test :backend:koverXmlReport :backend:koverVerify :backend:ktlintCheck :backend:detekt :backend:checkApiTypes :backend:contractLedgerCheck --offline -q`
Expected: PASS.

Run: `cd frontend && npm run typecheck && npm run test && npm run lint`
Expected: PASS (unchanged by this task; run it because `make test` does).

- [ ] **Step 12: Commit**

```bash
git add backend/src/test/kotlin/ca/floo/roadtrip/route/ContractLedgerCheck.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/api/settings/SettingsRoutesTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/PoiRoutesTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/AvailabilityDashboardRoutesTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/AdminIngestRoutesTest.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/auth/AuthRoutesTest.kt \
        backend/build.gradle.kts Makefile .github/workflows/ci.yml
git commit -F /dev/stdin <<'MSG'
test(api): six rows gain a 2xx test, and a ledger proves there are no more

PUT /api/settings/notifications, GET /api/pois/{id},
GET /api/availability/pollers/{id}/runs, GET /api/availability/changes,
GET /api/admin/data/runs/{id} and POST /auth/password/begin had no test that
produced their success body, so the body check never saw them. Each gets one, in
the suite that already owns its route file.

:backend:contractLedgerCheck then makes the gap unreintroducible. ContractBodyCheck
appends every checked response to build/contract-ledger/exercised.tsv, Gradle
clears it before the run and passes its path as a system property, and a JavaExec
over the test classpath reads it back: any VIOLATION line fails, and so does any
row with a success body nothing produced. A JavaExec rather than a second Test
task, so Kover's instrumented set and koverVerify's number are unchanged;
finalizedBy so the bare :backend:test in make test and in CI gates on it too.

Refs #754

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---
### Task 7: the docs say what is now caught

**Files:**
- Modify: `docs/backend-architecture.md` (the "The API Contract" section, ~lines 330-390), `AGENTS.md:27-30`

- [ ] **Step 1: Rewrite what the contract declares**

In `docs/backend-architecture.md`, replace the section's opening paragraph:

```markdown
`model/api/ApiContract.kt` declares every endpoint under `/api/**` and
`/auth/password/**` as data: its method (an `ApiMethod`, because `model/` never
names Ktor), its path as registered, its request DTO, its 2xx response DTO, and
the error DTOs it can serialize. Two things read it.
```

with:

```markdown
`model/api/ApiContract.kt` declares every endpoint under `/api/**` and
`/auth/password/**` as data: its method (an `ApiMethod`, because `model/` never
names Ktor), its path as registered, its request DTO, and — as an `ApiBody`
apiece — the 2xx it answers with and every non-2xx body it can serialize, each
with its status. The statuses are `HTTP_*` constants in `model/api/ApiStatus.kt`
for the same reason `ApiMethod` exists. Four things read it.
```

- [ ] **Step 2: Replace the "is not caught" sentence with what is now caught**

Replace:

```markdown
`ApiContractCoverageTest` exercises the comparator; the boot guard is what sees
the real tree. The guard compares paths in both directions; the DTOs on each row
are declared by hand, so a row pointing at the wrong body is not caught — feeding
`components/schemas` from `ApiContract` (backlog on #743) is what would close that.
```

with:

```markdown
`ApiContractCoverageTest` exercises the comparator; the boot guard is what sees
the real tree.

The DTO half of each row is held to the routes by
`route/ContractBodyCheck.kt`, a test-side plugin `routeTestApplication` installs.
On every JSON response whose matched route matches a row, it picks the class the
row names for that status, strict-decodes the body into it, re-encodes with
`roadtripApiJson`, and compares the two `JsonElement` trees. A row naming the
wrong DTO, a DTO that drifted from what the route serves, and a status no row
declares each fail the test that produced them. The strict decoder is the one
encoder with a single setting tightened — `ignoreUnknownKeys = false`; a route
serving a field its DTO lacks is the drift being hunted. `Json*` fields compare
as trees, so a `JsonNull` inside one round-trips honestly. A response on a path
no row names is skipped, which is what covers `/test/**` and `/data/**`.

`:backend:contractLedgerCheck` then holds the *suite* to the contract: the plugin
appends every checked response to `backend/build/contract-ledger/exercised.tsv`
and the task fails when a row with a success body was never produced by any test.
It is wired as `finalizedBy` on `:backend:test`, so the bare task `make test` and
CI run gates on it.
```

- [ ] **Step 3: Add the `/api/docs` subsection**

After the generator paragraphs and before the optionality bullets, insert:

```markdown
### `/api/docs`

`GET /api/docs/openapi.json` and the Swagger UI's own copy of the spec are built
in two halves, both through `roadtripApiJson` so they are byte-identical.

The **routing tree** contributes the paths — spelled by
`RoutingNode.path(OpenApiRoutePathFormat)`, which is the same spelling
`ApiContract` uses — plus the tag, summary and description each route declares
with `describeApi(...)`, and the path and query parameters Ktor infers from the
route selectors. The included surface is `RouteInventory.isContractedPath` plus
`/test/**`: `/api/**` and `/auth/password/**`, minus the whole `/api/docs/`
subtree.

`apigen/OpenApiContractDocument.apply` contributes the rest. `components/schemas`
comes from one walk of the whole contract — the same `walkContract` the
TypeScript generator reads, cached per process — so a schema name and a generated
`interface` name are one declaration rendered twice. Each row then fills its
operation's `requestBody` (`application/json`, a `$ref`, `required = true`) and
its `responses`: the success status, every declared error status, and an empty
response where the status carries no body. Two classes at one status become a
`oneOf`.

`401` and `403` are **not** on the rows where the tree already knows them: the
builder reads the route's declared `RouteAccess` through
`RouteInventory.declaredAccessByLeaf` and adds `401` for `User`, both for
`HasRole`, and neither for `Anonymous`, `Signed` or `UserOrCapability` — none of
which refuses anyone at that layer. Where a *handler* answers a 401 or a 403 on
such a route, the row says so, and the two sets are merged by (status, schema) so
nothing is published twice.

Schemas are rendered from the whole contract regardless of what is mounted, so
every `$ref` resolves even in a test that boots a slice of the tree. A row whose
operation is not mounted is skipped: the boot guard is the authority on a
non-`conditional` row with no route.

`additionalProperties: false` on a generated object schema is exact for a
response — the one encoder writes the declared elements and nothing else — and is
the contract's *statement* for a request, where `ignoreUnknownKeys = true` means
the decoder tolerates an extra key and promises nothing about it.
`io.ktor.openapi.JsonSchema` carries no `propertyNames`, so an enum-keyed map
publishes its value schema and not the union its keys are drawn from; the union
is still a component, because the walk reaches it.
```

- [ ] **Step 4: Update the loop**

Replace the loop block at the end of the section:

```
add the route  ->  add the ApiContract row  ->  make api-types  ->  commit the diff
```

with:

```
add the route  ->  add the row with its statuses  ->  make api-types  ->  commit the diff
```

and the sentence introducing it, so it names what the row now carries:

```markdown
The loop when you add or change an endpoint. "Its statuses" means every status
the handler can answer with a body, read off the handler — the body check fails
the first test that produces one the row does not declare:
```

- [ ] **Step 5: Update the `AGENTS.md` bullet**

Replace:

```markdown
- **The TypeScript API types are generated.** `frontend/src/api/generated/api-types.ts` comes
  from the Kotlin `@Serializable` DTOs named in `model/api/ApiContract.kt`. Never edit it by
  hand. Adding a route means adding a contract row; changing a DTO means running
  `make api-types` and committing the diff. `make test` and CI fail when it is stale.
```

with:

```markdown
- **The TypeScript API types and the `/api/docs` schemas are generated.**
  `frontend/src/api/generated/api-types.ts` and `components/schemas` in
  `/api/docs/openapi.json` are two renderings of one walk of the Kotlin `@Serializable` DTOs
  named in `model/api/ApiContract.kt`. Never edit the generated file by hand. Adding a route
  means adding a contract row **with the status of every body it serves**; changing a DTO means
  running `make api-types` and committing the diff. `make test` and CI fail when the file is
  stale, and `:backend:contractLedgerCheck` fails when a row's success body is never produced
  by a route test.
```

- [ ] **Step 6: Check the docs say nothing that is no longer true**

Run: `grep -rn "not caught\|by hand\|no components/schemas\|tags and summaries only" docs/backend-architecture.md AGENTS.md docs/frontend-components.md`
Expected: no hit claiming a wrong row goes uncaught, or that `/api/docs` carries no schemas.

- [ ] **Step 7: Run the whole gate, both halves**

Run: `make test`
Expected: PASS — the backend suite, kover, ktlint, detekt, `checkApiTypes`,
`contractLedgerCheck`, the detekt-rule tests, the frontend half, the script and secrets
tooling, and the Grafana validation.

Then, with the stack up (`make run`):

Run: `make qa`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add docs/backend-architecture.md AGENTS.md
git commit -F /dev/stdin <<'MSG'
docs: what the contract now catches, and where /api/docs' schemas come from

The "a row pointing at the wrong body is not caught" sentence is replaced by the
body check and the exercise ledger that catch it. A new /api/docs subsection says
which half of the document comes from the routing tree and which from the
contract, how a status reaches an operation, why 401 and 403 come from the
route's access level rather than from a row, and why additionalProperties: false
is exact for a response and a statement for a request. The loop gains the words
that matter: add the row *with its statuses*.

AGENTS.md's generated-types bullet now covers the schemas too.

Refs #754

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
MSG
```

---

## Deploy and live verification

Run after the branch merges, per the spec's Verification section. Not a task — no code changes.

1. `make test` green (the full local gate, including `:backend:checkApiTypes` and
   `:backend:contractLedgerCheck`) and `make qa` green against a running stack.
2. The body check demonstrably bites, both ways:
   - a wrong row — the scratch swap in Task 5, Step 9, reverted;
   - a route emitting a field its DTO lacks — add a stray key to a `respondText` in a route
     handler, run that route's test, watch the `strict-decode` failure name the DTO, revert.
3. Live on a `pg_dump` copy:
   - boot passes both guards (the RFC 0010 access guard and the contract guard);
   - `curl -s localhost:8765/api/docs/openapi.json | python3 -m json.tool > /dev/null` parses;
   - `components.schemas` has the count `:backend:test`'s `JsonSchemaRenderTest` reported;
   - every `$ref` resolves — the same walk `OpenApiSmokeTest` does, as a one-liner over the live
     document:
     ```bash
     curl -s localhost:8765/api/docs/openapi.json | python3 - <<'PY'
     import json, sys
     doc = json.load(sys.stdin)
     names = set(doc["components"]["schemas"])
     def refs(node):
         if isinstance(node, dict):
             for k, v in node.items():
                 if k == "$ref":
                     yield v
                 else:
                     yield from refs(v)
         elif isinstance(node, list):
             for v in node:
                 yield from refs(v)
     missing = sorted({r for r in refs(doc) if r.removeprefix("#/components/schemas/") not in names})
     print("schemas:", len(names), "paths:", len(doc["paths"]), "unresolved:", missing)
     PY
     ```
   - the Swagger UI at `/api/docs` renders `POST /api/watches` with a request body and a
     response schema, and `POST /api/availability/pollers/{id}/force` with a 429;
   - `/auth/password/begin` appears in the document, which it did not before.
4. The generated diff, reviewed by hand: `git show` the Task 2 commit's
   `frontend/src/api/generated/api-types.ts` and confirm no `export interface` or `export type`
   line moved — only the header and the 46 `API_ENDPOINTS` rows.

## Out of scope (backlog, note on #754)

- Runtime response validation in `http.ts`, and the typed fetch client over `API_ENDPOINTS`.
  The rows now carry statuses and types, which is what such a client needs; nothing consumes
  them yet.
- `format` annotations (`date-time` and friends) on string fields. The descriptor says
  `PrimitiveKind.STRING` for an `Instant`, and nothing in the walk knows it was a timestamp;
  carrying that would need an annotation on the DTO and a new `WireType` arm.
- Adopting or deleting `POST /api/pois/availability/bulk`. It still has no client anywhere in
  the repo; its schemas are now published and unused, which is at least honest.
- Serving `openapi.yaml`, or committing the document to the repo. It is derived at boot from
  committed sources, and `OpenApiDocSource.Routing` already knows how to serialize YAML if a
  consumer ever wants it.
- `propertyNames` on an enum-keyed map, when `io.ktor.openapi.JsonSchema` grows the field.
  `JsonSchemaRenderTest` pins the current gap so the upgrade that closes it is noticed.
- `ReadinessPoolExhaustionTest` on the harness. It measures a genuinely exhausted Hikari pool,
  and the row it covers is already exercised through `HealthRoutesTest`.
- A `oneOf` row in the real contract. The renderer and the builder handle two classes at one
  status and a fixture exercises it; no route serves two shapes at one status today.
