# OpenAPI schemas from the contract, and a body check behind every route test

**Follow-up to** the 2026-09-09 audit, finding 14 (#743, merged in #752). Issue #754.
**Branch:** `refactor/openapi-schemas`, base `3c026c01` (master after #753).

## Problem

`model/api/ApiContract.kt` names each endpoint's request, response and error DTO classes, and the boot guard holds the contract to the routing tree, but only by `(method, path)`. The DTO half of every row is hand-declared: a row that names the wrong body class passes the boot guard, `checkApiTypes` and `tsc`, and the frontend imports a type nothing serves. `docs/backend-architecture.md` says so in one sentence and points here.

`/api/docs/openapi.json` is built by Ktor from the routing tree with tags and summaries only. It has no `components/schemas` because no operation carries a schema: every handler responds through `respondEncodedJson`, which sends a pre-encoded string, so Ktor has no body type to infer from. The Ktor model itself (`io.ktor.openapi.OpenApiDoc`, 3.5.2) has everything needed: `components.schemas: Map<String, JsonSchema>`, `Operation.requestBody`, `Operation.responses: Map<Int, Response>`, `MediaType.schema: ReferenceOr<JsonSchema>`, first-class `$ref`.

Three facts shape the design:

- The TypeScript generator's descriptor walk (`apigen/DescriptorWalk.kt`) already encodes the encoder's real optionality (`encodeDefaults = true`, `explicitNulls = false`) and eight named refusals, but it renders straight to TypeScript strings; there is no structured type between the descriptor and the text. Ktor's own `buildJsonSchema(SerialDescriptor)` exists but does not know the encoder's optionality and reproduces none of the refusals.
- `ApiEndpoint.errors` is a set of classes with no status. `AvailabilityErrorDto` is served at 400, 404, 500, 501 and 503; `ErrorNotFoundSchema` at 400 and 404; two admin routes serve their 2xx DTO at 500; `POST /api/watches` answers 201; `POST /api/watches/{id}/delete` answers 204 with no body. 400 `ApiErrorSchema` is universal through `StatusPages`; 401/403 come from the row's `.access(...)` level; 429 exists once and carries `CheckNowCooldownDto`.
- No JSON-Schema validator is on the classpath. But there is exactly one deterministic encoder, so a body equals what a DTO produces iff a strict decode into that DTO followed by a re-encode reproduces the same JSON tree. `FeatureCollectionContractTest` already relies on byte-identical encoding.

## Goal

One structured type sits between the descriptor and both renderers, so the TypeScript file and the OpenAPI schemas are two views of the same walk with the same optionality and the same refusals. `ApiContract` carries statuses. `/api/docs/openapi.json` gets `components/schemas` and per-operation request and response bodies from the contract. Every JSON response produced in a route test is checked against the contract row for its method, path and status, so a row pointing at the wrong DTO, a DTO that drifted from what the route serves, or an undeclared status fails the build.

## Design

### 1. A structured wire type

`apigen/WireType.kt`:

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
    data class MapOf(val key: WireType, val value: WireType) : WireType   // key is Str, Num or EnumRef
}
```

`DescriptorWalk.typeOf` returns `WireType`; `TsField.type` becomes `WireType`; every refusal stays exactly where it is. `TsRender` renders `WireType` to the same TypeScript text as today: `integer` and `number` both render `number`, so `frontend/src/api/generated/api-types.ts` is byte-identical after the refactor apart from the `API_ENDPOINTS` change in §3 (proven by `checkApiTypes` before and after that change lands). `ApiTypeGeneratorTest` keeps asserting rendered text and passes unchanged through the refactor step.

### 2. The schema renderer

`apigen/JsonSchemaRender.kt` renders the same `interfaces` and `enums` maps to `io.ktor.openapi.JsonSchema`:

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
| `MapOf(k, v)` | `{type: object, additionalProperties: v}`; an `EnumRef` key adds `propertyNames: {$ref}` |
| interface | `{type: object, title: n, properties: {...}, required: [non-optional fields], additionalProperties: false}` |
| enum | `{type: string, title: n, enum: [serial names]}` |

`required` is the complement of the walk's optionality under the same rule as TypeScript: for a response class, a field is required iff it is non-nullable; for a request class, iff it is non-nullable and has no default. A class reachable from both roots must agree or generation fails (already the rule). No schema carries a `null` type, because the encoder never writes one; the `JsonNull`-inside-`Json*` caveat from #752 applies to `Any*` only and is documented on those rows. `additionalProperties: false` is accurate for responses (the encoder emits only declared keys) and is the contract's statement for requests even though the decoder tolerates extras; the doc says so.

Schema names are the same claimed names the TypeScript uses, so `components/schemas/CampsiteDto` and `interface CampsiteDto` are one declaration rendered twice.

### 3. Statuses in the contract

```kotlin
data class ApiBody(val status: Int, val body: KClass<*>?)   // body null only for 204 and empty-text answers

data class ApiEndpoint(
    val method: ApiMethod,
    val path: String,
    val request: KClass<*>? = null,
    val success: ApiBody,                                     // was response: KClass<*>?; default status 200
    val errors: List<ApiBody> = listOf(ApiBody(400, ApiErrorSchema::class)),
    val conditional: Boolean = false,
)
```

Rules for the rows:

- `success` is the 2xx the route answers with: 200 by default, 201 for `POST /api/watches`, 204 with no body for `POST /api/watches/{id}/delete`, 200 with no body for `POST /auth/password/complete` and `POST /api/slack/interactivity`.
- `errors` lists every non-2xx body the route itself serializes, one entry per (status, class): the availability routes list `AvailabilityErrorDto` at each status `CampsiteRoutes`/`BulkAvailabilityRoutes` actually use; the admin import routes list their 2xx DTO again at 500; the force-poller row lists `CheckNowCooldownDto` at 429; the admin runs row lists `ErrorNotFoundSchema` at 400 and 404. 400 `ApiErrorSchema` is the universal default (StatusPages). 401 and 403 are not on the rows: the document builder adds them from the route's access level (§4), because the tree knows it and the row must not restate it.
- The TypeScript `API_ENDPOINTS` row becomes `{ method, path, request, success: { status, type }, errors: [{ status, type }] }`; `frontend/src/api/api-endpoints.test.ts` reads `path` only and keeps working. A companion change to that test asserts every `success.type`/`errors[].type` names a declared interface.

`ApiContractCoverageTest` keeps `CONTRACT_ROW_COUNT = 46` and gains: every `ApiBody.status` is in 200..599; no row declares the same (status, class) twice; a 204 has no body.

### 4. The document

`ApiDocsRoutes` keeps building `OpenApiDoc(info) + roadtripOpenApiRoutes()` for paths, tags and summaries, then hands the document to `apigen/OpenApiContractDocument.apply(doc, contract, accessOf)`, which:

- renders `components.schemas` from one `DescriptorWalk` over the contract (the same call `generateApiTypes` makes; the result is cached per process);
- for each row, finds `paths[path]` and the operation for `method` (a row whose operation is absent fails the document build with the row named, which the boot guard already prevents; a `conditional` row that is not mounted is skipped);
- sets `requestBody` (`application/json`, `$ref`, `required = true`) when the row has a request;
- sets `responses[success.status]` with a `$ref` or an empty response for a body-less status, and `responses[status]` for each error entry (two entries at one status become `oneOf`);
- adds `responses[401]` and `responses[403]` referencing `ApiErrorSchema` when the route's `RouteAccess` is `User` or `HasRole` (read from the route attribute the access DSL already sets).

`OpenApiSmokeTest` gains: `components.schemas` is non-empty and its key set equals the set of names the TypeScript generator declares; every operation under `/api/**` has `responses`; every `$ref` in the document resolves; `POST /api/watches` shows `201`, a `requestBody`, and `401`; `POST /api/availability/pollers/{id}/force` shows `429` referencing `CheckNowCooldownDto`; `POST /api/watches/{id}/delete` shows `204` with no content. The three route files that never call `describeApi` (`RouteRoutes`, `GeocodeRoutes`, `SlackInteractivityRoutes`) gain a tag and summary so their operations are not bare.

`LayeringGuardTest` is unchanged: `apigen` is neither `model/` nor `service/`, and the contract itself stays Ktor-free (`ApiBody` is plain Kotlin).

### 5. The body check behind every route test

`backend/src/test/kotlin/ca/floo/roadtrip/route/ContractBodyCheck.kt`: a test-side Ktor plugin installed by `routeTestApplication` that, on every response whose content type is `application/json` and whose route matches a contract row by method and path template:

1. picks the class for the response status (`success` or the matching `errors` entry; a status with no entry fails the test naming method, path and status);
2. strict-decodes the body with `Json { ignoreUnknownKeys = false }` and the class's serializer;
3. re-encodes with `roadtripApiJson` and asserts the two `JsonElement` trees are equal;
4. records the (method, path, status) as exercised in a process-wide ledger.

Responses for paths outside the contract (`/test/**`, `/data/**`) are ignored. The `Json*` fields are compared as trees like everything else, so a `JsonNull` inside one round-trips honestly.

The three route tests that call bare `routing { }` instead of `routeTestApplication` (`AvailabilityDashboardRoutesTest`, `AdminIngestRoutesTest`, `SlackInteractivityRoutesTest`) switch to the harness so the plugin covers them; a test that legitimately asserts a non-JSON or non-contract response is unaffected.

Five rows have no 2xx-producing route test today and get one: `PUT /api/settings/notifications` (a happy-path stub), `GET /api/pois/{id}`, `GET /api/availability/pollers/{id}/runs`, `GET /api/availability/changes`, `GET /api/admin/data/runs/{id}`. `POST /auth/password/begin` gets a route test with a stubbed wiring that returns a begin response.

`ContractExerciseTest` (a JUnit `@AfterAll`-style aggregate is unreliable across classes, so it is a Gradle-ordered last test via a dedicated `contractLedger` test task, or, simpler, an assertion inside `ApiContractCoverageTest` over a static ledger file the plugin appends to under `build/`): every row with a body must have been exercised at its success status at least once in the run. The plan settles the mechanism; the requirement is that the suite fails when a row's success body is never produced by any test.

### 6. Docs

`docs/backend-architecture.md`: the "not caught" sentence is replaced by what is now caught and how; a short `/api/docs` subsection (what the tree contributes, what the contract contributes, how statuses and 401/403 get there); the loop becomes `add the route → add the row with its statuses → make api-types → commit the diff`; the request-`additionalProperties` note. `AGENTS.md`: the existing generated-types bullet gains "and `/api/docs` schemas".

### 7. Out of scope (backlog on #754)

- Runtime response validation in `http.ts`; the typed fetch client.
- `format` annotations (date-time and friends) on string fields.
- Adopting or deleting `POST /api/pois/availability/bulk`.
- Serving `openapi.yaml`, or committing the document to the repo (it is derived at boot from committed sources).

## Verification

1. Backend gate (tests, kover, ktlint, detekt, `checkApiTypes`) and frontend gate green. After the `WireType` refactor and before the `API_ENDPOINTS` change, `checkApiTypes` proves the TypeScript is byte-identical.
2. The body check demonstrably fails when a row names the wrong DTO (swap two structurally different classes on a row in a scratch change, run the affected route test, revert) and when a route emits a field the DTO lacks.
3. Live on a `pg_dump` copy: boot passes the guards; `/api/docs/openapi.json` parses, has `components.schemas` with the expected count, every `$ref` resolves (a small script), and the Swagger UI renders an operation with its schema; `make qa` green.
