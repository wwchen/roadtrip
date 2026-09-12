# API contract: generated from the DTOs, checked in CI

**Phase:** 5c of the architecture audit (`2026-09-09-architecture-audit.md`, finding 14). Issue #743.
**Branch:** `refactor/api-contract`, base `28533815` (master after #751).

## Problem

The frontend's API types are hand-written mirrors of the Kotlin DTOs. Seventy-nine exported TypeScript types in twenty-three files under `frontend/src/api/` carry forty-two "Mirrors XDto" comments; nothing checks them. Responses are parsed with `response.json() as Promise<T>` and no runtime validation, so the TS types are compile-time assertions with no evidence behind them.

What the inventory found:

- **Real drift today.** `campsite-api.ts` (the documented "closed mirror") omits `booking_provider`, which `CampsiteDto` emits. `poi-api.ts` has no mirrors at all: its search result omits `region`, types `id` as `number | string`, and marks four required server fields optional; `CampgroundDetail` declares 9 of the schema's ~55 fields and a cast papers over the rest. `booking-api.ts` types `provider_display` optional where the DTO requires it. `geocode-api.ts` types `bbox` as a 4-tuple where the DTO is `List<Double>?`.
- **Systematic inaccuracy.** The one wire encoder (`route/common/RouteResponses.kt`) runs with `explicitNulls = false` and `encodeDefaults = true`: null is never emitted and non-null defaults always are. Almost every optional field in the mirrors is typed `foo?: T | null`; the `null` arm is unreachable, and fields with non-null defaults are wrongly optional.
- **Vocabularies that are strings on the Kotlin side but unions on the TS side.** `WatchStatus`, `RecgovSessionState`, `RecgovLoginStatus`, `BookingActionStatus` are `object { const val }` plus a `String` field; the frontend hand-maintains the unions.
- **The three named symptoms.** `longest_run_nights` belongs to `POST /api/pois/availability/bulk`, an endpoint with no client anywhere in the repo, so there is no TS type to be missing from. The `triggerConfig`/`stopWhenTriggered` casts in `watch-triggers.ts` are dead: the backend has only ever emitted `trigger_config`/`stop_when_triggered`, and the only camelCase payload in existence is manufactured by that file's own test. `alert-rows.ts` infers why a watch ended from `end_date < today` because the two done-transitions (triggered in `WatchAlertDispatcher`, elapsed in `AvailabilityPollerRepo.reapElapsedWatches`) persist nothing that distinguishes them; the guess mislabels a watch triggered on its last day.
- **No enforcement anywhere.** The `/api/docs` OpenAPI spec is generated from the routing tree with only tags and summaries (no schemas, and no route exposes a body type because every handler responds with a pre-encoded string). CI path-filters backend and frontend into independent jobs that never compare. No commit in the repo's history attempted a cross-language contract check.

## Goal

The Kotlin `@Serializable` DTOs under `model/api/` are the single source of truth for the wire contract. A generator that runs inside the backend build, with no network or npm dependency, emits the TypeScript types from those DTOs' serialization descriptors, honouring the encoder's actual behaviour. The generated file is committed; `make test` and CI fail when it is stale. The frontend imports the generated types instead of maintaining mirrors, and the compiler surfaces the drift that exists today. The three named symptoms are fixed at their real causes.

## Design

### 1. The contract list

`model/api/ApiContract.kt` declares every HTTP endpoint the backend serves as data:

```kotlin
data class ApiEndpoint(
    val method: ApiMethod,            // GET, POST, PUT, DELETE (model/ must not import Ktor)
    val path: String,                 // as registered, e.g. "/api/pois/{id}/campsites"
    val request: KClass<*>? = null,   // body DTO for POST/PUT, null otherwise
    val response: KClass<*>? = null,  // 2xx body DTO, null for 204/redirect/empty
    val errors: List<KClass<*>> = listOf(ApiErrorSchema::class),
)

object ApiContract {
    val endpoints: List<ApiEndpoint> = listOf(/* ~45 rows, one per route */)
}
```

It covers everything under `/api/**` and `/auth/password/**` (the frontend-called surface plus admin, health, build-info, slack, and the bulk availability endpoint). Redirect-only auth routes are listed with no bodies. `LayeringGuardTest` allows `io.ktor` only under `route/` and the OIDC provider, hence the small `ApiMethod` enum.

`ApiContractCoverageTest` builds the real routing tree (the way `OpenApiSmokeTest` does) and asserts the set of `(method, path)` pairs under `/api/**` and `/auth/password/**` equals the contract's set, both directions. A new route without a contract entry fails the build; a contract entry without a route does too.

### 2. The generator

A Gradle task `:backend:generateApiTypes` (a `JavaExec` over a small `apiGen` source set that depends on `main`) walks `serializer(kclass).descriptor` for every request and response class in `ApiContract.endpoints`, transitively, and writes `frontend/src/api/generated/api-types.ts`. `:backend:checkApiTypes` runs the same generator into a temp file and fails if it differs from the committed one. Both are plain JVM code over `kotlinx-serialization-json` 1.11.0, already on the classpath.

Mapping rules, driven by descriptor kinds:

| Kotlin | TypeScript |
|---|---|
| `@Serializable` class | `export interface <SimpleName> { ... }`, keys from `@SerialName` (descriptor element names) |
| `String`, `Char` | `string` |
| `Int`, `Long`, `Short`, `Byte`, `Float`, `Double` | `number` (ids stay below 2^53; documented) |
| `Boolean` | `boolean` |
| `List<T>`, `Set<T>`, arrays | `T[]` |
| `Map<K, V>` | `Record<string, V>` |
| `@Serializable enum` | `export type <Name> = 'a' \| 'b' \| ...` from the constants' serial names |
| `JsonElement` | `unknown` |
| `JsonObject` | `Record<string, unknown>` |
| `JsonArray` | `unknown[]` |
| value class / custom serializer | whatever its descriptor's primitive kind says (an `Instant` serialized as a string is `string`) |
| sealed / polymorphic | generation fails with a message naming the class (none exist on the wire today; this keeps it that way until someone designs the discriminator mapping) |

Optionality follows the encoder, not the Kotlin type:

- **Response types:** a field is optional (`foo?: T`) iff it is nullable (`explicitNulls = false` omits null). A non-null field with a default is required (`encodeDefaults = true` always emits it). `| null` is never generated.
- **Request types:** a field is optional iff it is nullable or has a default (kotlinx accepts a missing element when it has a default; a nullable element without a default is also accepted as absent under `explicitNulls = false`). The same class used as both request and response gets one interface with response optionality plus a `Request` variant only when the two differ; the generator computes the difference and the plan settles the naming (`XDto` and `XDtoInput`, say).

Output is deterministic: interfaces sorted by name, fields in declaration order, a header line naming the generator and forbidding hand edits, LF line endings, a trailing newline. Name collisions between two classes with the same simple name fail generation. The file also emits an `export const API_ENDPOINTS` literal (method, path, request/response type names) so the frontend can, later, type its fetch helpers against it; nothing consumes it in this phase.

### 3. Vocabularies become enums

`WatchStatus`, `RecgovSessionState`, `RecgovLoginStatus`, `BookingActionStatus` become `@Serializable enum class`es with per-constant `@SerialName` equal to today's strings, and the DTO fields that carry them change from `String` to the enum. Persistence is unchanged: the repos keep reading and writing the same strings (`WatchStatus` already has a DB representation; the enum's serial name is that string). Anything that compared the field to a `const val` compares to the enum constant. The generated TS unions therefore replace the four hand-maintained ones exactly.

### 4. Why a watch ended is recorded, not guessed

Migration `V63__availability_watch_done_reason.sql`: `ALTER TABLE availability_watch ADD COLUMN done_reason text` with a CHECK `done_reason IS NULL OR done_reason IN ('triggered', 'elapsed')`. No backfill: existing `done` rows stay null.

- `WatchAlertDispatcher` sets `done_reason = 'triggered'` in the same update that sets `status = done` after a delivered alert with `stop_when_triggered`.
- `AvailabilityPollerRepo.reapElapsedWatches` sets `done_reason = 'elapsed'` in its one statement.
- `WatchDoneReason` is a `@Serializable enum` (`triggered`, `elapsed`); `AvailabilityWatchSchema` gains `@SerialName("done_reason") val doneReason: WatchDoneReason? = null`; the repo reads and writes it; the watch service passes it through.
- `alert-rows.ts` reads `done_reason` when present and keeps the date inference only as the fallback for pre-migration rows, with the comment saying exactly that.

Tests: dispatcher and reaper each pin the reason; a repo test round-trips it; the watch route test pins the wire key; the frontend `alert-rows` test covers `triggered`, `elapsed`, and the null fallback (including the last-day case the guess gets wrong).

### 5. The frontend adopts the generated types

- `frontend/src/api/generated/api-types.ts` is committed and imported; the client modules under `frontend/src/api/` keep their exported names as aliases (`export type Campsite = CampsiteDto;`) so the 55 non-test importers do not change, and delete every hand-written mirror interface and "Mirrors" comment. `poi-api.ts` gains aliases for the search hit, feature collection, detail properties, and category detail types it never mirrored; the `p as Partial<CampgroundDetail>` cast in `domain/poi/campground-detail.ts` goes because the type now has the fields.
- Compile errors the generated types surface are fixed at the use site: `booking_provider` on `Campsite`, `region` and the non-optional fields on the search hit, `provider_display` required, `bbox` as `number[]`. Where a component relied on `null` narrowing that can no longer happen, it narrows on `undefined`.
- `watch-triggers.ts` loses the `triggerConfig` and `stopWhenTriggered` casts; the two test cases that manufacture camelCase payloads go with them. The legitimate top-level `channel` legacy read stays.
- The fetch helpers in `http.ts` are unchanged in this phase (runtime validation is out of scope).
- `docs/frontend-components.md`'s "closed mirror" paragraph is rewritten: the closed type is generated; a new fact still means an ETL promotion and a DTO field, and now nothing else.

### 6. The gate

- `Makefile` `test` target runs `:backend:checkApiTypes` alongside the backend gate; `Makefile` gains `api-types` (`:backend:generateApiTypes`) for developers.
- `.github/workflows/ci.yml`: the backend job runs `checkApiTypes`; the path filter for that job includes `frontend/src/api/generated/**` so a hand edit to the generated file cannot slip through a frontend-only PR.
- `docs/backend-architecture.md` documents the contract list, the generator, the optionality rules, and "add a route → add a contract row → run `make api-types` → commit the diff". `AGENTS.md` gets one bullet: TS API types are generated; never edit `frontend/src/api/generated/`.

### 7. Out of scope (backlog, noted on #743)

- Feeding `components/schemas` in `/api/docs/openapi.json` from `ApiContract` (the list is shaped for it; the JSON-Schema emitter is a second generator).
- Runtime response validation in `http.ts`.
- Adopting or deleting `POST /api/pois/availability/bulk` (no client; the types are now generated and unused).
- A global `JsonNamingStrategy.SnakeCase` in place of 222 `@SerialName` annotations (wire-visible; separate change).
- The `*Dto` vs `*Schema` naming split in `model/api/`.
- Typing the fetch helpers against `API_ENDPOINTS`.

## Verification

1. Backend gate (`:backend:test :backend:koverXmlReport :backend:koverVerify :backend:ktlintCheck :backend:detekt :detekt-rules:test`) and `:backend:checkApiTypes` green; frontend `npm run lint && npm run test && npm run build && npm run build-storybook` green (this branch changes frontend code, so the frontend half of `make test` runs in full).
2. `checkApiTypes` demonstrably fails when a DTO gains a field without regenerating (add a field in a scratch change, run the check, revert).
3. Live on a `pg_dump` copy: V63 applies; `GET /api/watches` on a seeded done-by-trigger watch carries `done_reason: "triggered"`, an elapsed one `"elapsed"`, a pre-migration one omits the key; `GET /api/pois/{id}/campsites` still serves `booking_provider`; `/api/docs` still boots; `make qa` green.
4. Generated file diff against the deleted mirrors reviewed by hand: every removed union is present as a generated enum type; every removed field exists in the generated interface.
