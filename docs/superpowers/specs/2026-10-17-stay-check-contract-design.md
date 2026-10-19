# The stay check: one streamed contract

The bulk availability surface is two endpoints that answer different questions
under paths that claim to be the same one. One of them has never had a client.
This folds the surface down to the single streamed read the map actually uses,
and makes its shapes say what they mean.

Scope is the wire. The loader's freshness policy — see **Deferred** — is a
separate piece of work.

Five statements in this document were wrong or incomplete when written and were
caught during implementation or the final read of the branch. They are corrected
in place, each marked **Correction**, with what was claimed and why it did not
hold: the `observed_at` invariant (under `StaySummaryDto`, and again under
**Testing**); the claim that `AvailabilityResponseDto` is shared with the detail
endpoint (under `StaySummaryDto`, **Deletions** and **Risks**); the count of
`failed` reasons (under **Design**, the outcome table); the claim that no YAML
sets the renamed keys (under **Configuration**); and the `OpenApiSmokeTest`
assertion (under **Testing**).

## Problem

`POST /api/pois/availability/bulk` and `POST /api/pois/availability/bulk/stream`
ship together, share a route file, a controller, and a rate-limit bucket, and
agree on nothing else.

1. **The non-stream endpoint has no caller anywhere.** `api-endpoints.test.ts`
   pins it as unreached; `2026-09-10-availability-truth-design.md` confirms
   nothing in `frontend/`, `companion/` or `scripts/` calls it. "Adopting or
   deleting it" is written into the out-of-scope list of three separate specs
   (`api-contract`, `openapi-schemas`, `availability-truth`). It is live,
   anonymous, and charges the shared per-IP budget.
2. **The path lies.** `bulk/stream` is not `bulk` streamed. It is a per-POI
   stay verdict where `bulk` is a per-campsite detail dump, with a different
   request DTO and a different response body. A caller cannot reason from the
   URL.
3. **Two request DTOs differ by one field.** `BulkAvailabilityRequestDto` and
   `StayCheckRequestDto` carry verbatim-duplicated KDoc, and the route has two
   near-identical `validated()` functions.
4. **Both responses encode a sum type as optional fields.** `campsites? | error?`
   and `summary? | error?`, with "exactly one is set" asserted only in a comment.
   `stay-check.ts` pays for it: `UNCLASSIFIED_ERROR = 'unknown'` exists to cover
   the both-null case the contract claims is impossible.
5. **The error vocabulary lives in the client's head.** `error: String?` on the
   wire. `STAY_REFUSAL_CODES`, `STAY_TIMEOUT_CODE` and `UNCLASSIFIED_ERROR` are
   hand-typed literals in `availability-api.ts`, re-deriving a backend vocabulary
   spread across `AvailabilityProviderError`, `AvailabilityServiceError` and
   `STAY_TIMEOUT_ERROR`. The semantics the UI needs — retryable? the campground
   refusing the dates, or us failing to ask? — are classified in the browser.
6. **`done.answered` is a count nobody can use.** The client ignores it and
   checks `checkableIds.every(id => id in results)` instead, because a count
   cannot name the POI that is missing.
7. **`fresh: Boolean` contradicts the spec that introduced it.** Decision 7 of
   `2026-08-23-bulk-availability-design.md` said freshness is a service
   parameter, never a wire field. It shipped as a boolean standing in for a
   server-chosen 60s, so a caller cannot say "two minutes old is fine".
8. **`cache.ttl_seconds` is misnamed.** `AvailabilityLoader.effectiveTtlSeconds`
   computes `Duration.between(freshAtOrAfter, now)` — the tolerance that was
   applied, not time remaining.
9. **A failed POI loses its window.** `BulkPoiAvailabilityDto` echoes
   `start_date`/`end_date` on success and nulls them on error. The stream never
   echoes them at all.

## Current state (as mapped)

Both endpoints fan out through `BulkAvailabilityController` over the same
`CampsiteAvailabilityController.poiAvailabilitySlice`, under a
`Semaphore(fanOutConcurrency)`, with each POI's failure caught at the fan-out
boundary so one bad vendor never blanks the scan. A per-POI timeout answers
`timeout`; a whole-stream deadline answers `timeout` for everyone still waiting.
The reads are deliberately not the flow's children, so a read wedged in a
blocking JDBC call cannot hold the stream open past its deadline.

**None of that changes.** The fan-out, the deadlines, the failure isolation and
the shared slice are the parts that work. This spec only reshapes what crosses
the wire.

## Decisions

1. **One endpoint, summary only.** `POST /api/pois/availability/bulk` is
   deleted, not adopted. Per-campsite detail is already served by
   `GET /api/pois/{id}/campsites/availability`, which the drawer uses; a bulk
   caller that wants detail asks that, by id.
2. **The surviving read is SSE.** There is no non-streamed variant. A scan that
   fans out to one upstream call per campground has no business making the
   caller wait for the slowest one.
3. **The path names the resource, not the transport.**
   `POST /api/pois/availability/stays`.
4. **One request DTO.** `StayCheckRequestDto` survives; `BulkAvailabilityRequestDto`
   is deleted along with `min_nights`.
5. **The answer carries a required `outcome` enum.** Not a Kotlin sealed type —
   see Decision 6 — but a generated enum the client switches on, with the
   payload fields hanging off it.
6. **No sealed types on the wire, this time.** `DescriptorWalk.kt:90` refuses
   `PolymorphicKind` outright: *"the discriminator mapping is undesigned; design
   it before serving this type."* Designing it means changing `DescriptorWalk`,
   `TsRender` and `JsonSchemaRender`, which every contract row renders through.
   That is its own spec. A flat DTO with a required enum tag gets most of the
   value now, and a hand-written type predicate on the client gets the rest.
7. **The server states retryability.** The browser stops inferring it from an
   error string.
8. **`max_age_seconds` replaces `fresh`, and the server clamps it.** A duration
   where there was a boolean, bounded so a caller cannot force a refetch of every
   campground on every request.
9. **One PR, no deprecation window.** The only caller is our own frontend, the
   endpoint is anonymous but unadvertised, and `api-endpoints.test.ts` pins the
   call sites so a missed one fails the build.

## Design

### Request

```jsonc
POST /api/pois/availability/stays
{ "poi_ids": [12, 34, 56],     // 1..max_pois; request order is fan-out order
  "start_date": "2026-09-04",  // the first night
  "end_date":   "2026-09-11",  // the morning you leave
  "site_type":  ["tent"],      // CampsiteKind wire values
  "group_size": 4,             // optional
  "max_age_seconds": 7200 }    // optional
```

Unchanged from `StayCheckRequestDto` but for `fresh` → `max_age_seconds`. Both
dates stay mandatory, for the reason the current route already gives: with
either left null each POI resolves its own window from its own centroid
timezone, and the answers stop being comparable across POIs.

Request order remains load-bearing — POIs start in the order given, so the
caller sends the nearest first — and stays documented on the field rather than
only in the route description.

### The `answer` event

One per requested POI, as it lands, and never fewer.

```kotlin
@Serializable
data class StayAnswerDto(
    @SerialName("poi_id") val poiId: Long,
    val outcome: StayOutcome,
    /** Set exactly when [outcome] is ANSWERED. */
    val stay: StaySummaryDto? = null,
    /** Set exactly when [outcome] is REFUSED or FAILED. */
    val reason: StayFailureReason? = null,
    /** Whether asking again could help. Set exactly when [outcome] is FAILED. */
    val retryable: Boolean? = null,
)
```

```jsonc
{ "poi_id": 12, "outcome": "answered",  "stay": { … } }
{ "poi_id": 34, "outcome": "refused",   "reason": "beyond_booking_horizon" }
{ "poi_id": 56, "outcome": "timed_out" }
{ "poi_id": 78, "outcome": "failed",    "reason": "upstream_5xx", "retryable": true }
```

`StayOutcome` and `StayFailureReason` are `@Serializable` enums, so they
generate into `api-types.ts` and the client's hand-typed code lists are deleted.

| `outcome` | `reason` | what the client does |
|---|---|---|
| `answered` | — | `stay` is set; render the card |
| `refused` | `start_before_earliest`, `beyond_booking_horizon`, `window_too_long` | the dates cannot work here; terminal |
| `timed_out` | — | did not answer in time; Check again is worth offering |
| `failed` | `rate_limited`, `upstream_blocked`, `upstream_5xx`, `upstream_unreachable`, `provider_misconfigured`, `unsupported`, `not_found`, `unknown_campground`, `unknown` | `retryable` says whether asking again can help |

`refused` is strictly about the dates — exactly today's `STAY_REFUSAL_CODES`,
now stated by the server. `not_found` sits under `failed` with
`retryable: false` rather than under `refused`, so an unknown POI id never
renders as "these dates do not work here". `end_before_start` and
`bad_date_window` do not appear: the route refuses those for the whole request
before any fan-out, since an inverted window is wrong for every POI.

`retryable` is true for `rate_limited`, `upstream_blocked`, `upstream_5xx` and
`upstream_unreachable`, and false for `provider_misconfigured`, `unsupported`,
`not_found`, `unknown_campground` and `unknown`. It is absent on `refused` (never
retryable) and on `timed_out` (always worth retrying), so the field is present
exactly where it carries information.

> **Correction.** As first written, the table and this paragraph listed eleven
> `StayFailureReason` members. The enum has twelve: `unknown_campground` was
> added during implementation and the spec never caught up. It is kept apart from
> `not_found` on purpose. `not_found` means no campground row is linked to the POI
> id; `unknown_campground` means the campground resolved but no provider claims
> it. A POI that exists with no provider is not a POI that does not exist, and
> folding the two would tell a caller the latter. The UI renders them alike; the
> distinction is for logs and for the wire. It is terminal (`retryable: false`)
> and pinned by `WireVocabularyTest`.

### `StaySummaryDto`

`BulkPoiSummaryDto` renamed to what it is, with an honest freshness block.

```kotlin
@Serializable
data class StaySummaryDto(
    val status: StayAvailabilityStatus,
    @SerialName("open_sites") val openSites: Int,
    @SerialName("checked_sites") val checkedSites: Int,
    /**
     * When the oldest observation behind this answer was seen. Absent when no
     * observation backs the answer: no campsite matched the filters, or campsites
     * matched and the provider returned nothing for the window.
     */
    @SerialName("observed_at") val observedAt: String? = null,
    /** False when at least one campsite-day was refetched from the provider for this answer. */
    @SerialName("from_cache") val fromCache: Boolean,
)
```

`StayAvailabilityStatus` and the `staySummary()` logic behind it are unchanged.

The embedded `AvailabilityCacheBlock` goes. It was nullable ("absent when
nothing was read"), its `ttl_seconds` was the applied tolerance rather than a
TTL, and its `age_seconds` is a relative number the client immediately converts
back to an instant (`observedAtOf`). `observed_at` is that instant, sent
directly. `AvailabilityCacheBlock` itself stays: the detail endpoint's
`PoiCampsitesAvailabilityResponseDto` still carries it as `cache`, and its shape
is not in scope here.

> **Correction.** As first written, this paragraph said `AvailabilityResponseDto`
> carried the block on the detail endpoint, and that the only change to that DTO
> was dropping `longest_run_nights`. The detail endpoint serves
> `PoiCampsitesAvailabilityResponseDto`, not `AvailabilityResponseDto`; the latter
> appeared in no contract row and was reachable only through the deleted scan's
> body. See **Risks**.

`observed_at` is the one residual optional field. Its absence means no
observation backs the answer, which happens two ways, and a reader should be able
to tell them apart: no campsite matched the filters (`checked_sites` is 0,
including when `group_size` is what emptied the list), or campsites matched and
the provider returned nothing for the window (`checked_sites` is above 0, and the
status is `no_data`). The absence is the two cases' shared consequence, not
`checked_sites == 0` alone.

> **Correction.** As first written, this paragraph claimed `observed_at` is
> "absent exactly when `checked_sites == 0`", arguing that
> `poiAvailabilitySlice` returns `batch = null` only in its `campsites.isEmpty()`
> branch. That is true of `batch`, but the invariant does not follow from it.
> `observed_at` is the `minOfOrNull` over the batch's observations, and that is
> also null when the batch exists with no observations — a provider that answered
> nothing for the window — where `checked_sites` is above 0. The rule is: absent
> when no observation backs the answer. The same statement under **Testing** is
> corrected to match.

### The `done` event

```kotlin
@Serializable
data class StayCheckDoneDto(
    val outcome: StayCheckOutcome,   // COMPLETE | DEADLINE_REACHED
)
```

`answered: Int` is deleted. It was always `poi_ids.size` when it arrived at all,
and a count cannot name the POI that is missing.

`outcome` carries what the client genuinely cannot derive: whether the check
ended because every campground answered, or because the whole stream hit
`stream-timeout` and the rest were answered `timed_out` on its behalf. Today
those are indistinguishable — every POI *did* answer, so `useStayCheck` reports
a deadline-truncated check as a clean success. With this, a `DEADLINE_REACHED`
check can say so.

A stream that closes with no `done` at all still means cut off, as now.

### Errors before the stream

Unchanged: a refused request answers JSON with a status, before any event.
`bad_request` (malformed body, unknown `site_type`, `too_many_pois`,
`bad_group_size`, `bad_date_window`, `end_before_start`) at 400, `ip_throttled`
at 503. Both as `AvailabilityErrorDto`, both already contract rows.

### Freshness

`max_age_seconds` is clamped into `[minMaxAge, defaultMaxAge]` and defaults to
`defaultMaxAge`. Those are today's `freshTolerance` (60s) and `tolerance` (2h)
renamed, so the two behaviours that exist now — `fresh: true` and `fresh: false`
— become the two ends of the range rather than a boolean.

The floor is what stops a caller forcing `0` and refetching every campground on
every request; it also means a double-click does not ask every provider twice.
The ceiling keeps the server in charge of how stale is too stale to serve at all.

The service API below the route is untouched: the controller still turns this
into `freshAtOrAfter: Instant` exactly as it does today.

### Layering

Unchanged.

```
BulkAvailabilityRoutes          HTTP shell: parse, validate, cap, SSE framing
        |
BulkAvailabilityController      fan-out, deadlines, outcome mapping
        |
CampsiteAvailabilityController  poiAvailabilitySlice (shared with the detail endpoint)
```

The one addition is a mapper from the controller's `PoiRead` to a
`StayAnswerDto` — today's `answerOf`, extended to classify the failure into an
`outcome`/`reason`/`retryable` triple. That classification belongs in the
service layer, not the route, and not the browser.

## Deletions

| What | Where |
|---|---|
| `POST /api/pois/availability/bulk` route + contract row | `BulkAvailabilityRoutes.kt`, `ApiContract.kt` |
| `BulkAvailabilityRequestDto`, `BulkAvailabilityResponseDto`, `BulkPoiAvailabilityDto` | `model/api/` |
| `availabilityForPois`, `rank`, `BulkAvailabilityRequest` | `BulkAvailabilityController.kt` |
| `min_nights`, `DEFAULT_MIN_NIGHTS`, `bad_min_nights` | request DTO, route validation |
| `longestRunNights` + `AvailabilityRunLengths.kt` + `AvailabilityRunLengthsTest.kt` | `service/availability/` |
| `longest_run_nights` field | `AvailabilityResponseDto` |
| `STAY_REFUSAL_CODES`, `isStayRefusal`, `STAY_TIMEOUT_CODE`, `UNCLASSIFIED_ERROR` | `availability-api.ts`, `stay-check.ts` |
| the `/bulk` half of `BulkAvailabilityRoutesTest` and `BulkAvailabilityControllerTest` | `backend/src/test/` |

`longest_run_nights` is written only by the deleted `rank()`, so it was always
null. Removing it from `AvailabilityResponseDto` is a consequence of the deletion,
not a separate change.

> **Correction.** As first written, this said the field had been null "on the
> detail endpoint" and that `AvailabilityResponseDto` is shared with it. It is not:
> the detail endpoint serves `PoiCampsitesAvailabilityResponseDto`, so the field was
> never on that endpoint's wire. `AvailabilityResponseDto` survives as an internal
> type; it left the generated types with the scan's body.

> **Follow-up.** It did not survive for long. With the scan gone its only
> remaining callers were the rec.gov and Aspira oracle tests, so the whole chain
> behind it — `perCampsiteEnvelopes`, `CampsiteEnvelope`,
> `availabilityResponseFromObservations`, `availabilityResponseDto`,
> `AvailabilityResponseDto`, and the `DayClassification` shaping under them —
> was deleted in a follow-up change, and those tests now assert vendor parsing
> against the `AvailabilityObservationBatch` the adapters produce. A
> `@Serializable` type sitting in `model/api/` with no contract row is the kind
> of thing a future route picks up by autocomplete and starts serving
> ungoverned.

## Configuration

`BulkAvailabilityConfig` keeps every bound it has. Two renames:

| Today | After |
|---|---|
| `tolerance` | `default-max-age` |
| `fresh-tolerance` | `min-max-age` |

Nothing outside `backend/src` sets any `roadtrip.availability.*` key — no deploy
config, compose file or CI workflow — so the rename costs nothing operationally.
The existing invariants (`minMaxAge <= defaultMaxAge`,
`ipPoiLimitPerMinute >= maxPois`) carry over unchanged, with their `require`
messages updated to the new names.

> **Correction.** This section also listed `fanOutConcurrency < db.max-pool-size`
> among the invariants carried over "with their `require` messages updated". It was
> never one: `BulkAvailabilityConfig.init` did not check it and neither does
> `StayCheckConfig.init`. It was a comment, and measurement has since shown it to be
> false in both directions — a POI read holds no pooled connection across its vendor
> call, and three times `max-pool-size` in concurrent reads queued inside
> `db.connection-timeout` without one acquisition timeout. The comment is gone; what
> `fan-out-concurrency` really bounds is stated in `StayCheckConfig` and in
> `docs/backend-architecture.md`.

> **Correction.** As first written, this section said no deploy config, compose
> file or YAML set any `availability.bulk.*` key, because "the defaults all live in
> `BulkAvailabilityConfig.Companion`". That reason was false.
> `backend/src/main/resources/application.yaml` set five of them (`max-pois`,
> `fan-out-concurrency`, `tolerance`, `fresh-tolerance`, `ip-rate-limit-per-minute`),
> including both keys this section renames, and now sets eight under
> `roadtrip.availability.stays.*`. The conclusion holds for a different reason:
> that file is in `backend/src`, so the rename changed it in the same commit as the
> code that reads it, and nothing outside `backend/src` (compose, deploy workflow,
> secrets registry) sets a `roadtrip.availability.*` key that an operator would
> have to migrate.

## The client

`availability-api.ts` loses its three hand-typed code lists and gains one
narrowing predicate, because the generated `StayAnswerDto` is a flat type and
TypeScript cannot prove `stay` is set from `outcome === 'answered'`:

```ts
export interface AnsweredStay extends StayAnswerDto { outcome: 'answered'; stay: StaySummaryDto }
export function isAnswered(a: StayAnswerDto): a is AnsweredStay { return a.outcome === 'answered'; }
```

That predicate is the whole cost of Decision 6, and it is deleted the day
apigen learns discriminators.

`stay-check.ts`'s `resultOf` switches on `outcome` instead of truthiness.
`stayTallies` reads `outcome` and `reason` instead of matching code strings —
its `refused`, `timedOut` and `unknown` buckets become direct reads rather than
inferences. `observedAtOf` is deleted: `observed_at` arrives as an instant.
`useStayCheck`'s completion check becomes `done.outcome === 'complete'`, which
is both simpler and more honest than today's `completed && every id in results`.

## Staging

1. **Backend shapes.** New DTOs and enums beside the old ones, the outcome
   mapper in the controller, the new route, the new contract row. Old route
   still registered.
2. **`make api-types`**, committed.
3. **Frontend cutover** to the new endpoint and shapes.
4. **Delete** the old route, DTOs, contract row, dead helpers and their tests.
5. **Config rename**, with `BulkAvailabilityConfigTest` updated.

Steps 1–3 keep the build green with both surfaces live; step 4 is the one that
cannot land before the frontend moves. Each step is a commit.

## Testing

- `BulkAvailabilityRoutesTest`: one case per `outcome`, including a `refused`
  POI alongside an `answered` one in the same stream, and `retryable` present
  exactly on `failed`.
- A stream that hits `stream-timeout` ends with `done.outcome = deadline_reached`;
  one that does not ends `complete`.
- `max_age_seconds` below the floor and above the ceiling both clamp; absent
  defaults to `default-max-age`. Assert the `freshAtOrAfter` the controller
  derives, not just the status code.
- `observed_at` absent when no observation backs the answer, in both cases: no
  campsite matched (`checked_sites == 0`), and campsites matched but the provider
  returned nothing for the window (`checked_sites > 0`, `no_data`). *(Corrected:
  this line first read "absent exactly when `checked_sites == 0`"; see
  `StaySummaryDto`.)*
- `ContractBodyCheckTest` / `contractLedgerCheck`: both events produced by a
  route test, or the build fails.
- `api-endpoints.test.ts`: `CALLED_TODAY` loses `/api/pois/availability/bulk/stream`
  and gains `/api/pois/availability/stays`; the `/bulk` exemption comment goes
  with the endpoint.
- ~~`OpenApiSmokeTest`: assert the deleted path is absent, as it already does for
  `/api/availability/bulk`.~~ **Correction: this assertion was dropped as
  vacuous.** `OpenApiSmokeTest` never mounts the availability routes, so a
  `paths` lookup for a deleted availability path is absent whether or not the
  route was deleted; the existing `/api/availability/bulk` assertion it was meant
  to follow is vacuous for the same reason. It was replaced by a check that can
  fail: `ApiContractCoverageTest` asserts the contract holds exactly one row at
  `/api/pois/availability/stays` and none whose path starts with
  `/api/pois/availability/bulk`.

## Deferred, with reasoning

- **Discriminator support in apigen.** Decision 6. The generator's own error
  message asks for it, and it would delete the `isAnswered` predicate and make
  the three "set exactly when" KDocs into types. It touches every contract row,
  so it gets its own spec rather than riding along with a route change.
- **Per-cell cache top-up.** `AvailabilityLoader.loadOrFetch` decides freshness
  over the whole (campground × window) batch: `hasFullCoverage` requires every
  cell present and `isFreshAsOf` requires every cell fresh, so one stale cell in
  a 20-site × 7-night window refetches all 140. Storage is already keyed by
  `(campsite_id, target_date)`, so a partial top-up is possible. Out of scope
  here — it changes no wire shape — but it is the larger win.
- **`recordFetched` discards observations outside the current `site_type`
  filter.** `observation.campsiteId?.takeIf { it in targetIds } ?: continue`
  drops cells the vendor already returned and we already paid for, leaving
  coverage incomplete for the next unfiltered read. A bug, filed with the item
  above, and not a contract change.
- **Incremental asks.** Letting a client say "these 8 are new, I hold the other
  40" would stop a map pan spending per-POI budget on answers already in hand.
  Additive to the request DTO later; it needs the client to report what it holds
  and how old, which is a bigger design than this clean-up.
- **A request id echoed on the stream.** Staleness is prevented today by client
  `AbortController` discipline, which works. Worth it only if a second consumer
  appears.

## Risks

- ~~**`AvailabilityResponseDto` is shared.** Removing `longest_run_nights` touches
  the detail endpoint's generated type. It has always been null there, and
  nothing in `frontend/src` reads it, but the regenerated `api-types.ts` diff
  will be larger than the route change suggests.~~ **Correction: this risk did not
  exist.** The detail endpoint serves `PoiCampsitesAvailabilityResponseDto`;
  `AvailabilityResponseDto` appeared in no contract row and was reachable only
  through the deleted scan's body. It left `api-types.ts` along with the three
  `Bulk*` DTOs, and nothing the detail endpoint serves changed.
- **The frontend cutover is not purely mechanical.** `stayTallies` currently
  derives its buckets from code strings; moving them to `outcome`/`reason` is a
  behaviour change wherever the two disagree. The existing `stay-check.test.ts`
  is the gate.
- **`done.outcome = deadline_reached` makes a previously-silent case visible.**
  Checks that today report success while truncated will start reporting that they
  ran out of time. That is the point, but it will look like a regression in the
  UI until the copy accounts for it.
