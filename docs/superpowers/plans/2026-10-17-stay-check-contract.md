# Stay Check Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse the two bulk availability endpoints into one SSE read at `POST /api/pois/availability/stays` whose answers carry a required outcome enum, a generated failure vocabulary, server-stated retryability, and `max_age_seconds` in place of the `fresh` boolean.

**Architecture:** The fan-out, deadlines and failure isolation in `BulkAvailabilityController` are unchanged — only what crosses the wire is reshaped. A new pure classifier turns today's scattered error-code strings into an `(outcome, reason, retryable)` triple in the service layer, so the browser stops inferring retryability from strings. The answer stays a flat DTO with an enum tag rather than a Kotlin sealed type, because `DescriptorWalk.kt:90` refuses `PolymorphicKind` outright; one hand-written TypeScript predicate recovers the narrowing.

**Tech Stack:** Kotlin + Ktor + kotlinx.serialization (backend), the repo's own apigen (`:backend:generateApiTypes`), React + TypeScript + Zustand + Vitest (frontend).

**Spec:** `docs/superpowers/specs/2026-10-17-stay-check-contract-design.md`

## Global Constraints

- Every commit must leave `make test` green. The backend DTO shape, the regenerated `frontend/src/api/generated/api-types.ts`, and the frontend code that reads it are one atomic unit — `:backend:checkApiTypes` fails on a stale generated file and `npm run build` fails on a frontend that reads a removed field, so a task that changes a wire shape changes all three.
- Never hand-edit `frontend/src/api/generated/api-types.ts`. Run `make api-types` and commit the diff.
- Adding or changing a route means updating its row in `model/api/ApiContract.kt` with the status of every body it serves. `:backend:contractLedgerCheck` fails when a row's success body is never produced by a route test.
- No inline magic constants: numeric, string and duration literals at call sites become named `const val`, or env-driven config when operationally tunable (AGENTS.md).
- Layering is `routes -> service -> repo`. Routes parse inputs, call a service, set status codes and return DTOs. The failure classification in Task 1 is service-layer, not route-layer.
- `org.jooq` appears only under `repo/`, `db/` and `di/InfraModule.kt`; `LayeringGuardTest` fails the build otherwise. No task here touches persistence.
- Wire enum values are the exact snake_case strings in the spec's outcome table. `WireVocabularyTest` guards wire vocabulary.

## Review Focus

Five things the spec implies that no task's happy path exercises. Each has a test pinned to the task that owns the code.

1. **A newer server sends an `outcome` or `reason` the browser has never heard of.** This change makes the client switch on enum values where it previously read free strings, so an added enum member must degrade to "unknown", not throw. Today the client already skips unknown *events*; unknown enum *values* are new risk this change introduces. — Task 2.
2. **`max_age_seconds` of `0`, a negative, or a year.** The field is caller-supplied and the floor is the only thing stopping a refetch of every campground on every request. It must clamp silently, not 400 and not obey. — Task 4.
3. **Duplicate ids in `poi_ids`.** The controller answers per index, but the client keys results by `poi_id`, so a duplicate collapses and the stream carries more answers than the client records — which would make `done` arrive with the client still believing a POI is missing. — Task 2.
4. **A POI that answers after the stream deadline.** `stayAnswers` cancels the read scope in its `finally`, but a read already past its `trySend` guard must not emit an answer after `done`. — Task 3.
5. **`observed_at` absent while `checked_sites > 0`.** The spec claims this is impossible because `batch == null` implies `campsites.isEmpty()`; the `group_size`-emptied path reaches the same branch by a different route and must behave the same. — Task 2.

---

### Task 1: The failure classifier and the outcome vocabulary

Pure, additive, nothing else changes. Turns an error-code string into the triple the wire will carry.

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/StayOutcome.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/StayFailureReason.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/StayFailureClassifier.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/StayFailureClassifierTest.kt`

**Interfaces:**
- Consumes: `AvailabilityProviderError.Unknown.CODE`, the `error` strings on `AvailabilityServiceError` subclasses, `STAY_TIMEOUT_ERROR` in `BulkAvailabilityController.kt`.
- Produces:
  - `enum class StayOutcome { ANSWERED, REFUSED, TIMED_OUT, FAILED }` with `@SerialName` `answered` / `refused` / `timed_out` / `failed`.
  - `enum class StayFailureReason` with `@SerialName` `start_before_earliest`, `beyond_booking_horizon`, `window_too_long`, `rate_limited`, `upstream_blocked`, `upstream_5xx`, `upstream_unreachable`, `provider_misconfigured`, `unsupported`, `not_found`, `unknown`.
  - `internal data class StayFailure(val outcome: StayOutcome, val reason: StayFailureReason, val retryable: Boolean?)`
  - `internal fun stayFailureOf(code: String): StayFailure`

- [ ] **Step 1: Write the failing tests**

```kotlin
class StayFailureClassifierTest {
    @Test fun `a horizon refusal is refused and carries no retryable`() {
        assertEquals(StayFailure(StayOutcome.REFUSED, StayFailureReason.BEYOND_BOOKING_HORIZON, null),
            stayFailureOf("beyond_booking_horizon"))
    }

    @Test fun `the timeout code is its own outcome with no reason-bearing retryable`() {
        assertEquals(StayOutcome.TIMED_OUT, stayFailureOf("timeout").outcome)
        assertNull(stayFailureOf("timeout").retryable)
    }

    @Test fun `an upstream failure is failed and retryable`() {
        assertEquals(StayFailure(StayOutcome.FAILED, StayFailureReason.UPSTREAM_5XX, true), stayFailureOf("upstream_5xx"))
    }

    @Test fun `a misconfigured provider is failed and not retryable`() {
        assertEquals(StayFailure(StayOutcome.FAILED, StayFailureReason.PROVIDER_MISCONFIGURED, false),
            stayFailureOf("provider_misconfigured"))
    }

    @Test fun `an unknown poi is failed and not retryable, never refused`() {
        assertEquals(StayFailure(StayOutcome.FAILED, StayFailureReason.NOT_FOUND, false), stayFailureOf("not_found"))
    }

    @Test fun `a code outside the vocabulary falls back to unknown and not retryable`() {
        assertEquals(StayFailure(StayOutcome.FAILED, StayFailureReason.UNKNOWN, false), stayFailureOf("end_before_start"))
    }

    @Test fun `every provider and service error code classifies`() {
        // Every `code` on AvailabilityProviderError's subclasses and every `error` on
        // AvailabilityServiceError's, minus the two the route refuses up front, maps to a
        // non-UNKNOWN reason. Enumerate them literally; the point is that adding a provider
        // error without classifying it fails here.
    }
}
```

Retryability, from the spec: true for `rate_limited`, `upstream_blocked`, `upstream_5xx`, `upstream_unreachable`; false for `provider_misconfigured`, `unsupported`, `not_found`, `unknown`; null for `REFUSED` and `TIMED_OUT`, where it carries no information.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :backend:test --tests '*StayFailureClassifierTest'`
Expected: FAIL — unresolved reference `stayFailureOf`.

- [ ] **Step 3: Implement the two enums and `stayFailureOf(code: String): StayFailure`**

A `when` over the code string. `end_before_start` and `bad_date_window` deliberately fall to the `UNKNOWN` branch rather than getting members — the route refuses them for the whole request before any fan-out, so they cannot reach a per-POI answer.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :backend:test --tests '*StayFailureClassifierTest' :backend:ktlintCheck :backend:detekt`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/model/api/StayOutcome.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/StayFailureReason.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/availability/StayFailureClassifier.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/availability/StayFailureClassifierTest.kt
git commit -m "feat(availability): a stay's failure classifies into an outcome, a reason, and whether asking again helps"
```

---

### Task 2: The `answer` event's new shape, end to end

The wire shape, the regenerated types and the frontend that reads them, in one commit — see Global Constraints. The path stays `/api/pois/availability/bulk/stream` for now.

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/StaySummaryDto.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/StayAnswerDto.kt`
- Delete: `BulkPoiSummaryDto` from `backend/src/main/kotlin/ca/floo/roadtrip/model/api/BulkAvailabilityResponseDto.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/StaySummary.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/BulkAvailabilityController.kt:196-203` (`answerOf`)
- Modify: `frontend/src/api/generated/api-types.ts` (generated — do not hand-edit)
- Modify: `frontend/src/api/availability-api.ts`
- Modify: `frontend/src/features/trip/stay-check.ts`
- Modify: `frontend/src/features/trip/useStayCheck.ts`
- Modify: `frontend/src/stores/stayCheckStore.ts`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/StaySummaryTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/BulkAvailabilityControllerTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/route/api/pois/BulkAvailabilityRoutesTest.kt`, `frontend/src/features/trip/stay-check.test.ts`, `frontend/src/features/trip/useStayCheck.test.tsx`

**Interfaces:**
- Consumes: `StayOutcome`, `StayFailureReason`, `StayFailure`, `stayFailureOf` (Task 1).
- Produces:
  - `StaySummaryDto(status: StayAvailabilityStatus, openSites: Int, checkedSites: Int, observedAt: String?, fromCache: Boolean)` with `@SerialName` `status`, `open_sites`, `checked_sites`, `observed_at`, `from_cache`.
  - `StayAnswerDto(poiId: Long, outcome: StayOutcome, stay: StaySummaryDto?, reason: StayFailureReason?, retryable: Boolean?)`, `EVENT = "answer"` unchanged.
  - `internal fun PoiAvailabilitySlice.staySummary(): StaySummaryDto` (replaces the `BulkPoiSummaryDto` return).
  - TS: `isAnswered(a: StayAnswerDto): a is AnsweredStay` exported from `availability-api.ts`.
  - TS: `StayResult = { kind: 'summary'; summary: StaySummaryDto } | { kind: 'error'; outcome: StayOutcome; reason: StayFailureReason | null; retryable: boolean | null }` in `stayCheckStore.ts`.

- [ ] **Step 1: Write the failing backend tests**

In `StaySummaryTest.kt`:

```kotlin
@Test fun `a summary reports the oldest observation behind it as an instant`()
    // observed_at equals the earliest observedAt in the batch, ISO-8601, not an age.

@Test fun `from_cache is true when the loader served without asking the provider`()
    // batch.cacheBlock.hit == true -> fromCache == true.

@Test fun `observed_at is absent exactly when no campsite was checked`()
    // Two cases, both checkedSites == 0, both observedAt == null:
    //   a) the site_type filter left no campsites (slice.batch == null);
    //   b) group_size emptied the list after the slice resolved (batch non-null, campsites empty).
    // Review Focus 5.
```

In `BulkAvailabilityControllerTest.kt`:

```kotlin
@Test fun `a resolved poi answers with outcome answered and a stay`()
@Test fun `a rate-limited poi answers failed, rate_limited, retryable`()
@Test fun `a poi past its booking horizon answers refused with no retryable`()
@Test fun `a poi that outlives the per-poi timeout answers timed_out with no reason`()
@Test fun `duplicate poi ids each get their own answer`()
    // poiIds = listOf(7L, 7L) yields two answers, both poi_id 7. Review Focus 3.
```

In `BulkAvailabilityRoutesTest.kt`, over the live stream:

```kotlin
@Test fun `an answer event carries outcome and omits the fields that do not apply`()
    // answered -> has "stay", no "reason", no "retryable"
    // refused  -> has "reason", no "stay", no "retryable"
    // failed   -> has "reason" and "retryable"
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :backend:test --tests '*StaySummaryTest' --tests '*BulkAvailabilityControllerTest' --tests '*BulkAvailabilityRoutesTest'`
Expected: FAIL — `StaySummaryDto` unresolved, `StayAnswerDto` has no `outcome`.

- [ ] **Step 3: Implement the backend shape**

Create `StaySummaryDto`; delete `BulkPoiSummaryDto`; reshape `StayAnswerDto`; change `staySummary()` to build `StaySummaryDto` — `observedAt` from the batch's **earliest** observation (the loader's `cacheBlock.ageSeconds` is a max age, so the instant it corresponds to is the oldest), `fromCache` from `cacheBlock.hit`, both null/false in the no-batch branch; change `answerOf` to use `stayFailureOf` for the `PoiRead.Failed` case and `StayOutcome.ANSWERED` for the resolved one.

- [ ] **Step 4: Run the backend tests to verify they pass**

Run: `./gradlew :backend:test --tests '*StaySummaryTest' --tests '*BulkAvailabilityControllerTest' --tests '*BulkAvailabilityRoutesTest'`
Expected: PASS.

- [ ] **Step 5: Regenerate the API types**

Run: `make api-types`
Expected: `frontend/src/api/generated/api-types.ts` gains `StayOutcome`, `StayFailureReason`, `StaySummaryDto`; `StayAnswerDto` loses `summary` and `error`.

- [ ] **Step 6: Write the failing frontend tests**

In `stay-check.test.ts`:

```ts
it('reads a refused answer from its outcome, not from a code list')
it('reads retryability from the server rather than inferring it')
it('tallies an outcome it has never seen as unknown')
  // resultOf({ poi_id: 1, outcome: 'some_future_outcome' as StayOutcome })
  // must not throw, and stayTallies must count it under `unknown`. Review Focus 1.
it('ranks and tones an unrecognised outcome as unknown')
  // STATUS_TONE / STATUS_RANK lookups must not return undefined for an unknown value.
```

- [ ] **Step 7: Implement the frontend cutover**

`availability-api.ts`: delete `STAY_REFUSAL_CODES`, `StayRefusalCode`, `isStayRefusal`, `STAY_TIMEOUT_CODE`; re-export `StayOutcome`, `StayFailureReason`, `StaySummaryDto`; add `AnsweredStay` and `isAnswered`.
`stay-check.ts`: delete `UNCLASSIFIED_ERROR` and `observedAtOf`; `resultOf` switches on `outcome` via `isAnswered`; `stayTallies` reads `outcome` for its `refused`/`timedOut`/`unknown` buckets, with an unrecognised outcome falling to `unknown`.
`useStayCheck.ts`: `record(...)` takes `answer.stay.observed_at` directly instead of calling `observedAtOf`.
`stayCheckStore.ts`: `StayResult`'s error arm carries `outcome`/`reason`/`retryable` instead of `code`.

- [ ] **Step 8: Run the frontend tests and the type check**

Run: `cd frontend && npm run lint && npm run test && npm run build`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/model/api/ \
        backend/src/main/kotlin/ca/floo/roadtrip/service/availability/ \
        backend/src/test/kotlin/ca/floo/roadtrip/ frontend/src/
git commit -m "feat(availability): a stay answer says which outcome it is, and the server states whether asking again helps"
```

---

### Task 3: The `done` event says whether the check was truncated

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/StayCheckOutcome.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/StayStreamItem.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/StayCheckDoneDto.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/BulkAvailabilityController.kt:159-194` (`stayAnswers`)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/route/api/pois/BulkAvailabilityRoutes.kt:83-90`
- Modify: `frontend/src/features/trip/useStayCheck.ts:134-153`, `frontend/src/api/availability-api.ts`
- Test: `BulkAvailabilityControllerTest.kt`, `BulkAvailabilityRoutesTest.kt`, `useStayCheck.test.tsx`

**Interfaces:**
- Consumes: Task 2's reshaped `StayAnswerDto`.
- Produces:
  - `enum class StayCheckOutcome { COMPLETE, DEADLINE_REACHED }`, `@SerialName` `complete` / `deadline_reached`.
  - `StayCheckDoneDto(outcome: StayCheckOutcome)`, `EVENT = "done"` unchanged.
  - The flow's element type widens so the terminal outcome reaches the route: `fun stayAnswers(request: StayCheckRequest): Flow<StayStreamItem>`, where `StayStreamItem` is `Answer(StayAnswerDto)` or `Done(StayCheckOutcome)` — an internal service type, not a wire type, so it may be a sealed interface.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `a check where every poi answers in time ends complete`()
@Test fun `a check that hits the stream deadline ends deadline_reached`()
    // Every still-waiting POI answers timed_out AND the done event says deadline_reached —
    // today those POIs answer and the check reads as a clean success.
@Test fun `a read that finishes after the stream deadline sends nothing`()
    // Release a stalled read after the deadline; assert no further `answer` event
    // follows `done`. Review Focus 4.
```

In `useStayCheck.test.tsx`:

```tsx
it('reports a deadline-reached check as timed out even though every campground answered')
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :backend:test --tests '*BulkAvailabilityControllerTest' --tests '*BulkAvailabilityRoutesTest'`
Expected: FAIL — `StayCheckOutcome` unresolved.

- [ ] **Step 3: Implement**

`stayAnswers` already computes `finished == null` to decide the sweep's error code; emit the same decision as a terminal `StayStreamItem.Done`. The route's collector sends `answer` or `done` per item and drops its own `answered` counter. `useStayCheck`'s completion check becomes `done.outcome === 'complete'`, replacing `completed && checkableIds.every(id => id in results)`.

- [ ] **Step 4: Run the backend and frontend tests**

Run: `./gradlew :backend:test --tests '*BulkAvailability*'` then `cd frontend && npm run test && npm run build`
Expected: PASS.

- [ ] **Step 5: Regenerate types and commit**

```bash
make api-types
git add backend/src frontend/src
git commit -m "fix(availability): a stay check that ran out of time says so, instead of reading as a clean finish"
```

---

### Task 4: `max_age_seconds` replaces `fresh`

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/StayCheckRequestDto.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/route/api/pois/BulkAvailabilityRoutes.kt` (`validated`)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/BulkAvailabilityController.kt:205` (`freshAtOrAfter`), `StayCheckRequest`
- Modify: `frontend/src/api/availability-api.ts` (`StayCheckRequest`, `streamStays` body), `frontend/src/features/trip/useStayCheck.ts`
- Test: `BulkAvailabilityRoutesTest.kt`, `BulkAvailabilityControllerTest.kt`

**Interfaces:**
- Consumes: Task 2's and Task 3's shapes.
- Produces: `StayCheckRequestDto.maxAgeSeconds: Long?` (`@SerialName("max_age_seconds")`); `StayCheckRequest.maxAge: Duration` — already clamped by the time it leaves the route.

While in this DTO, move the request-order rule onto `poiIds` as KDoc — "POIs start in this order, so send the nearest first" is load-bearing and currently lives only in the route's OpenAPI description.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `max_age_seconds below the floor clamps up to it`()
    // 0 and -1 both resolve to config.freshTolerance. Review Focus 2.
@Test fun `max_age_seconds above the ceiling clamps down to it`()
    // 31_536_000 resolves to config.tolerance. Review Focus 2.
@Test fun `an absent max_age_seconds uses the default`()
@Test fun `a clamped value is not a bad request`()
    // All of the above answer 200 and stream, never 400.
```

Assert the `freshAtOrAfter` the controller derives, not just the status code — inject a fixed `Clock` and a fake `PoiAvailabilitySliceLookup` that records the instant it was called with, as the existing tests already do.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :backend:test --tests '*BulkAvailabilityRoutesTest' --tests '*BulkAvailabilityControllerTest'`
Expected: FAIL — unknown field `max_age_seconds`.

- [ ] **Step 3: Implement**

Clamp in the route's `validated()`, into `[config.freshTolerance, config.tolerance]`, defaulting to `config.tolerance` when absent. (The config fields keep today's names until Task 6; use `freshTolerance` and `tolerance` here and rename in one place later.) `freshAtOrAfter` becomes `Instant.now(clock).minus(request.maxAge)` and loses its `fresh` branch. Frontend: `fresh: boolean` on `StayCheckRequest` becomes `maxAgeSeconds: number | null`, and `useStayCheck`'s `run(fresh)` passes the floor value for a re-check — name it, do not inline the number.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :backend:test --tests '*BulkAvailability*'` then `cd frontend && npm run test && npm run build`
Expected: PASS.

- [ ] **Step 5: Regenerate types and commit**

```bash
make api-types
git add backend/src frontend/src
git commit -m "feat(availability): a caller says how stale an answer may be, and the server clamps it"
```

---

### Task 5: Move to `/api/pois/availability/stays` and delete the per-campsite scan

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/route/api/pois/BulkAvailabilityRoutes.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/ApiContract.kt:329-354`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/BulkAvailabilityRequestDto.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/model/api/BulkAvailabilityResponseDto.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityRunLengths.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityRunLengthsTest.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/BulkAvailabilityController.kt` (drop `availabilityForPois`, `rank`, `BulkAvailabilityRequest`)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/AvailabilityResponseDto.kt` (drop `longestRunNights`)
- Modify: `frontend/src/api/availability-api.ts:38` (`STAY_STREAM_URL`), `frontend/src/api/api-endpoints.test.ts:44-65`
- Test: `BulkAvailabilityRoutesTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/OpenApiSmokeTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/route/ContractBodyCheckTest.kt:42`, `backend/src/test/kotlin/ca/floo/roadtrip/apigen/OpenApiContractDocumentTest.kt:38`

**Interfaces:**
- Consumes: everything from Tasks 2–4.
- Produces: `POST /api/pois/availability/stays` as the only bulk availability route. One contract row: `StayCheckRequestDto` in; 200 with the `answer` and `done` events; 400 and 503 as `AvailabilityErrorDto`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `the stay check answers at its own path`()                 // POST /api/pois/availability/stays -> 200
@Test fun `the per-campsite scan is gone`()                          // POST /api/pois/availability/bulk -> 404
```

In `OpenApiSmokeTest.kt`, alongside the existing `/api/availability/bulk` assertion:

```kotlin
assertFalse(paths.containsKey("/api/pois/availability/bulk"))
```

In `api-endpoints.test.ts`: `CALLED_TODAY` loses `/api/pois/availability/bulk/stream` and gains `/api/pois/availability/stays`; the comment's `/api/pois/availability/bulk` exemption goes with the endpoint.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :backend:test --tests '*BulkAvailabilityRoutesTest' --tests '*OpenApiSmokeTest'`
Expected: FAIL — the new path 404s, the old one still answers.

- [ ] **Step 3: Delete and move**

Remove the `/availability/bulk` block and rename `/availability/bulk/stream` to `/availability/stays`; delete the two request/response DTO files, `BulkPoiAvailabilityDto`, `availabilityForPois`, `rank`, `BulkAvailabilityRequest`, `AvailabilityRunLengths.kt` and its test, the `bulkValidationErrorCodes` entry for `bad_min_nights`, and the `longest_run_nights` field; collapse `ApiContract.kt`'s two rows into one; update the route's `describeApi` description to the new body and remove the `min_nights` sentence; point `STAY_STREAM_URL` and the two test-file path constants at the new path.

`longest_run_nights` is written only by the deleted `rank()`, so removing it from the shared `AvailabilityResponseDto` changes no served value — but it does change the detail endpoint's generated type, so expect a larger `api-types.ts` diff than the route change suggests.

- [ ] **Step 4: Run the full suite**

Run: `make test`
Expected: PASS, including `:backend:checkApiTypes`, `:backend:contractLedgerCheck` and `OpenApiContractDocumentTest`.

- [ ] **Step 5: Commit**

```bash
make api-types
git add backend/src/main/kotlin/ca/floo/roadtrip/route/api/pois/BulkAvailabilityRoutes.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/ \
        backend/src/main/kotlin/ca/floo/roadtrip/service/availability/BulkAvailabilityController.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/ \
        frontend/src/api/
git add -u backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityRunLengths.kt \
           backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityRunLengthsTest.kt
git commit -m "feat(availability)!: the stay check moves to /api/pois/availability/stays, and the per-campsite scan goes"
```

Stage explicit paths, never `git add -A`: this task deletes files across
`backend/src`, and a blanket add would sweep up anything else in the working
tree that is not this task's.

---

### Task 6: Rename the freshness config

Last, so the rename lands in one place rather than being threaded through Tasks 4 and 5.

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/config/BulkAvailabilityConfig.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/BulkAvailabilityController.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/route/api/pois/BulkAvailabilityRoutes.kt` (the `describeApi` text)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/config/BulkAvailabilityConfigTest.kt`

**Interfaces:**
- Produces: `BulkAvailabilityConfig.defaultMaxAge` (was `tolerance`, config key `default-max-age`) and `BulkAvailabilityConfig.minMaxAge` (was `freshTolerance`, config key `min-max-age`).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `the config reads the renamed keys`()
@Test fun `min-max-age above default-max-age is refused`()
    // The existing freshTolerance <= tolerance invariant, under its new names,
    // with the require message naming the new keys.
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :backend:test --tests '*BulkAvailabilityConfigTest'`
Expected: FAIL — unresolved `defaultMaxAge`.

- [ ] **Step 3: Rename**

Both properties, both `fromConfig` keys, the `require` messages, and the KDoc. No deploy config, compose file or YAML sets any `availability.bulk.*` key — every default lives in `BulkAvailabilityConfig.Companion` — so nothing outside the backend changes. Leave `maxPois`, `fanOutConcurrency`, `ipPoiLimitPerMinute` and the three timeouts alone.

- [ ] **Step 4: Run the full suite**

Run: `make test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "refactor(config): the bulk read's tolerances are named for the max age they bound"
```

---

### Task 7: Update the provider documentation

**Files:**
- Modify: `docs/reservation-providers.md:467-472`

- [ ] **Step 1: Rewrite the two paragraphs**

They currently describe `POST /api/pois/availability/bulk` carrying one `AvailabilityResponseDto` per campsite, and the stay stream at `/bulk/stream`. One endpoint now: `POST /api/pois/availability/stays`, one `answer` event per POI carrying a `StaySummaryDto` or an outcome, per-campsite detail on `GET /api/pois/{id}/campsites/availability`.

- [ ] **Step 2: Verify no stale path references remain**

Run: `grep -rn "availability/bulk" docs/ backend/src frontend/src --include='*.md' --include='*.kt' --include='*.ts'`
Expected: hits only in `docs/superpowers/specs/` and `docs/superpowers/plans/`, which are dated records and stay as written.

- [ ] **Step 3: Commit**

```bash
git add docs/reservation-providers.md
git commit -m "docs(providers): the availability API is one streamed stay check"
```
