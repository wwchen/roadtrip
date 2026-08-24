# Bulk POI availability endpoint

**Date:** 2026-08-23
**Status:** Approved (design)

## Problem

There is no way to ask for availability across many POIs in one call. A trip
planner scanning twenty candidate campgrounds must issue twenty
`GET /api/pois/{id}/campsites/availability` requests.

The ask: one `POST` taking `(poi_ids, start_date, end_date, min_nights)` that
returns each POI's campsite availability, with each POI's campsites ordered by
the longest run of consecutive bookable nights.

**This endpoint is synchronous.** It fans out, waits, and returns. Deferred
work, background continuation, and a `pending` response state were considered
and are explicitly out of scope — see Deferred below.

## Current state (as mapped)

- **Read path.** `CampsiteRoutes` → `CampsiteAvailabilityController.availabilityForPoi`
  → `CampsiteAvailabilityService.fetchAvailability` → `AvailabilityLoader.loadOrFetch`
  → `FailoverAvailabilityFetcher` → adapter. One campground per call.
- **Snapshot-vs-vendor is already internal and already correct.**
  `AvailabilityLoader.loadOrFetch` reads `AvailabilityRepo.readCurrent`, checks
  `hasFullCoverage` + `isFresh`, and only on a miss calls the fetch lambda,
  writes through via `recordObservations`, then re-reads. Nothing above it sees
  the distinction. **This design does not change that responsibility.**
- **Windows are not silently clamped, but the bounds are per provider.**
  `AvailabilityDateResolver.resolveWindow` *throws*
  `BadDateWindow.WindowTooLong` / `BeyondBookingHorizon` rather than narrowing,
  so a POI that succeeds returns exactly the requested window. The thresholds it
  throws against differ by provider:

  | Provider | `maxPollWindowDays` | `bookingHorizonDays` |
  | --- | --- | --- |
  | rec.gov | 60 | 180 |
  | Campflare | 60 | 365 |
  | Aspira | 30 | 365 |
  | ReserveAmerica | 30 | 270 |
  | ReserveCalifornia | 30 | 183 |

  So one request can succeed for some POIs and fail for others: 31 nights is
  fine for rec.gov and Campflare and `window_too_long` for the rest; 200 days
  out is fine for Aspira and `beyond_booking_horizon` for rec.gov. A second
  per-POI variance is `earliestDate`, derived from the POI centroid's timezone
  plus an 18:00 cutoff, which can make one POI reject a start date another
  accepts.

  **This is by design, not a defect.** Ranking is *within* a POI (Decision 4);
  nothing here ranks POIs against each other, so the windows need not agree. A
  POI whose provider cannot serve the requested window reports a per-POI error
  like any other failure.
- **Two freshness vocabularies.** The poller uses
  `AvailabilityRepo.hasFreshCoverage(…, freshAtOrAfter: OffsetDateTime)` — an
  absolute instant in the SQL predicate. The read path uses
  `AvailabilityFreshness.isFresh(observedAts, now, ttl: Duration)`, whose first
  line is `val freshAfter = now.minus(ttl)`. One concept, two spellings, with
  one deriving the other immediately.
- **Campflare's client is bulk-capable and we throw it away.**
  `CampflareAvailabilityClient.fetchAvailability(campgroundIds: List<String>, …)`
  accepts up to `MAX_BULK_CAMPGROUNDS`, but `CampflareAvailabilityProvider`
  calls it with `listOf(campgroundId)` because the `AvailabilityProvider` port
  is per-campground.
- **Nothing computes consecutive runs.** `countConsecutiveFailures` in
  `AvailabilityRunRepo` is unrelated (poller run history).

## Decisions

1. **Synchronous.** Fan out concurrently under a semaphore, await all, respond.
2. **Same code path as the detail endpoint.** The controller loops over the
   existing per-POI use case. No second read path to drift.
3. **A "night" is a date cell where `AvailabilityStatus.isOnlineBookable`** —
   `AVAILABLE` only. `FIRST_COME` cannot be reserved, so it does not count.
4. **`min_nights` filters campsites, never POIs.** Response entries stay 1:1
   with `poi_ids`, so "no match here" is distinguishable from "unknown POI" and
   "vendor failed".
5. **Ordering is within a POI.** Campsites sort by longest run descending; POI
   order follows the request.
6. **Partial failure returns 200** with a per-POI error. One flaky vendor must
   not blank the whole scan.
7. **Freshness is a service-API parameter, not a wire field.** Callers of the
   availability service name `freshAtOrAfter: Instant`; HTTP clients do not.
   No `force_refresh` in any spelling — that would put the caller in charge of
   the snapshot/vendor decision. The existing `checkNow` →
   `AvailabilityPollerRepo.forcePull` path stays the imperative escape hatch.

## Design

### Contract

```
POST /api/pois/availability/bulk
{ "poi_ids": [12, 34, 56], "start_date": "2026-09-04",
  "end_date": "2026-09-11", "min_nights": 3, "site_type": ["tent"] }
```

```jsonc
{ "pois": [
  { "poi_id": 12, "campsites": [ { /* AvailabilityResponseDto */,
                                   "longest_run_nights": 5 } ] },
  { "poi_id": 34, "error": "rate_limited" },
  { "poi_id": 56, "campsites": [] }          // no site met min_nights
]}
```

200 whenever the request itself was well-formed. `campsites` is filtered to
`longest_run_nights >= min_nights` and sorted descending. Error codes reuse the
existing `AvailabilityErrorDto` vocabulary via `mapProviderError`, plus
`not_found`, `timeout`, and the `BadDateWindow` codes.

Each campsite envelope already carries `cache: { hit, age_seconds, ttl_seconds }`
— `AvailabilityLoader.batchFromLatest` computes `maxAgeSeconds` today. The
server reports the age it served rather than promising a freshness the caller
asked for.

### Layering

```
BulkAvailabilityRoutes            HTTP shell: parse, cap, status codes
        |
BulkAvailabilityController        fan-out, runs, min_nights, sort
        |
CampsiteAvailabilityController    existing per-POI use case (unchanged)
        |
CampsiteAvailabilityService  ->  AvailabilityLoader (cache coherency)
```

The controller calls the same per-POI method the detail endpoint calls, once
per POI, inside a `Semaphore`-bounded `coroutineScope`. Each POI's failure is
caught and mapped at that boundary, so one failure never propagates out of the
fan-out.

`availabilityForPoi` currently also resolves `watchCapabilities`, which bulk
does not need. Rather than add a boolean flag, its body splits into a
`poiAvailabilitySlice(...)` returning the campground, campsites, window, and
observation batch; the detail endpoint maps slice + watch capabilities into
today's DTO, and bulk maps slice → runs. Both callers share one implementation.

### Freshness: `freshAtOrAfter: Instant`

`AvailabilityLoader.Request.ttl: Duration` becomes `freshAtOrAfter: Instant`,
supplied by the caller. `ApiCacheEntity.*_AVAILABILITY.defaultTtl` does not
disappear — it becomes how a caller *derives* its instant.

Why absolute rather than relative: with a duration, each POI's `Instant.now()`
drifts across the fan-out, so a POI evaluated late is held to a stricter
*cache-freshness* standard than one evaluated early — the same stored snapshot
could count as fresh for one POI and stale for another in the same request,
purely from scheduling order. Computing the cutoff once removes that. It also
unifies the read path with the poller and with the SQL predicate that already
takes `freshAtOrAfter`.

(This is about which POIs re-fetch, not about run lengths. Run lengths are never
compared across POIs — see Decision 4.)

No clamp or floor is built: callers are internal, trusted code.

> **Correction (post-implementation).** This section originally claimed
> vendor-load protection "already lives in `VendorRateLimiter` and
> `ProviderCooldownTracker`". That is false for this path. `VendorRateLimiter`
> is wired only into `AvailabilityPollExecutor`; nothing on the read path
> consults it. `ProviderCooldownTracker.sortHealthyFirst` only reorders
> candidates and never excludes one, so with the common single-provider
> campground it is a no-op. The real brakes on this endpoint are the snapshot
> cache, `max-pois`, `fan-out-concurrency`, and `IpRateLimiter`. Do not size
> this endpoint on the assumption that a vendor-side limiter will catch
> overflow.

### Runs

`AvailabilityRunLengths.longestRunNights(observations): Int` — a pure function
over `CampsiteDayObservation`, counting the longest consecutive sequence of
dates whose status satisfies `isOnlineBookable`. No I/O, no dependencies.

### Bulk-capable providers (P2)

- `AvailabilityProviderCapabilities.maxBulkCampgrounds: Int`, default `1`
  (no bulk) — a declared limit alongside `maxPollWindowDays`.
- `AvailabilityProvider.catalogAvailabilityBulk(groups, window)` returning
  per-campground batches **and** per-campground errors, never throwing for the
  whole set. The **default implementation** calls today's `catalogAvailability`
  per campground, so every existing adapter keeps working untouched.
- `CampflareAvailabilityProvider` overrides it with one call, chunked by
  `maxBulkCampgrounds`.

This is the only part that requires the fan-out to know POIs share providers,
so it is staged after the endpoint works.

### Configuration

New `AvailabilityConfig.bulk` section — no inline constants:

| Key | Purpose |
| --- | --- |
| `max-pois` | Reject requests over this many POIs |
| `fan-out-concurrency` | Semaphore bound on concurrent POI resolution |
| `per-poi-timeout` | One hung vendor must not hang the request |
| `tolerance` | Derives `freshAtOrAfter` for this endpoint |
| `ip-rate-limit-per-minute` | Distinct from the detail endpoint's 30 |

Defaults are set in the implementation plan.

## Staging

**P1 — the endpoint.** `AvailabilityRunLengths`; the
`poiAvailabilitySlice` extraction; `BulkAvailabilityController` with bounded
fan-out; `freshAtOrAfter` replacing `ttl`; the route, DTOs, and config. Fully
shippable; no provider or persistence changes.

**P2 — bulk-capable provider path.** `maxBulkCampgrounds`,
`catalogAvailabilityBulk` with its default implementation, and the Campflare
override. Pure optimization: no contract change, no response-shape change.

## Testing

- **`AvailabilityRunLengths`** — pure unit tests: empty window, all available,
  all reserved, run at each boundary, multiple runs (longest wins),
  `FIRST_COME` interrupting a run, `min_nights` boundary at exactly N.
- **Controller** — POI entries 1:1 with the request, including duplicate and
  unknown ids; `min_nights` filtering; sort order; one POI failing while others
  succeed; per-POI timeout producing an error rather than failing the request;
  concurrency bounded by the semaphore.
- **`freshAtOrAfter`** — one cutoff applied to every POI in a fan-out with an
  advancing clock; behavior parity with the old TTL for the detail endpoint.
- **P2** — per-campground failures isolated inside a bulk call; chunking
  respects `maxBulkCampgrounds`; the Campflare override issues one HTTP call
  for N campgrounds; every non-overriding adapter is unaffected.
- **Regression** — existing single-POI availability tests pass unchanged. That
  is the gate on the `poiAvailabilitySlice` extraction.

## Deferred, with reasoning

- **Asynchronous continuation and a `pending` state.** Considered in detail and
  cut. Returning partial results at a deadline while unresolved fetches
  continue in the background requires either a second background fetch loop
  beside the poller, or read-triggered enrollment into the poller — which is
  the wrong instrument, since a poller is a recurring schedule justified by
  watch demand (default cadence 300s) and its executor also writes run rows and
  drives `WatchAlertDispatcher`. A one-shot deferred fetch is not a
  subscription. If bulk latency proves unacceptable in practice, revisit with
  measurements; the sync contract does not have to change to add it, because a
  timeout error and a `pending` state occupy the same slot.
- **A per-request freshness field on the wire.** The only difference from
  server policy is who can change tolerance without a deploy, and no current
  caller needs a different tolerance than another. Adding an optional field
  later is backward compatible; removing one clients depend on is not.
- **Ranking POIs against each other.** Explicitly out of scope — Decision 4
  sets ranking *within* a POI. A caller that wants to rank POIs must account for
  the per-provider window bounds above: a POI absent from the results may have
  been rejected by its provider's limits rather than have nothing available.
- **Reporting every qualifying run window per site**, not just the longest.
  Additive to the DTO later if the planner needs specific stay windows.

## Risks

- **Latency is bounded by the slowest POI**, up to `per-poi-timeout`. This is
  the accepted cost of a synchronous contract and the main thing to measure
  before considering the deferred design above.
- **A cold scan of N POIs is up to N vendor calls.** Mitigated by the existing
  snapshot cache, by `max-pois`, by `fan-out-concurrency`, by `IpRateLimiter`,
  and — for Campflare — by P2. **Not** mitigated by `VendorRateLimiter` or
  `ProviderCooldownTracker` — see the correction under Freshness. Worth
  watching in the `availability_fetch_call` Grafana panels after launch.
- **`fan-out-concurrency` must stay below `db.max-pool-size`.** Each cold POI
  holds a pooled connection through a row-by-row write transaction in
  `AvailabilityRepo.recordObservations`. The pool defaults to 4 and is set in no
  config file, so the fan-out ships at 3. Raising one without the other
  reproduces pool exhaustion and trips `/api/health/ready`. The invariant is
  stated in comments at both sites but is not enforced in code.
- **The `poiAvailabilitySlice` extraction touches the live detail endpoint.**
  Behavior parity via the existing tests is the gate.

## Follow-ups from the P1 whole-branch review

Raised by the final review of `feat/bulk-availability-p1`, judged non-blocking
and deliberately deferred. Recorded here so the next person inherits them.

1. **The shared-code goal is only half met.** Both endpoints share the *fetch*
   via `poiAvailabilitySlice`, but the per-campsite envelope shaping is
   duplicated verbatim between `CampsiteAvailabilityController` and
   `BulkAvailabilityController`. A new envelope field must be added twice and
   the compiler will not say so. Extract a shared mapper — this is the spec's
   own central claim, and the duplication is still two identical blocks rather
   than two drifted ones.
2. **No overall request deadline.** `withTimeout` bounds one POI, not the
   request. With `max-pois: 50` and `fan-out-concurrency: 3`, a fully cold scan
   where every POI times out runs far past the per-POI budget. Add an overall
   timeout and return `timeout` for POIs that do not finish inside it.
3. **`"timeout"` is unreliable, and blames the vendor.**
   `FailoverAvailabilityFetcher.attemptFetch` catches `Throwable`, so a
   `TimeoutCancellationException` during a vendor call is recorded as
   `FetchOutcome.OTHER` and surfaces as `upstream_5xx`. Narrow that catch to
   re-throw `CancellationException`.
4. **Blocking JDBC weakens the per-POI timeout.** The repo calls inside
   `poiAvailabilitySlice` are blocking, not suspending, so cancellation cannot
   interrupt a POI wedged on a slow query or a connection-acquisition wait.
5. **Sizing was not revisited.** The review recommended `max-pois` 50 → 20 and
   `ip-rate-limit-per-minute` 10 → 3. Left at 50/10 as a product decision. As
   shipped, one caller can drive up to 500 cold vendor calls per minute. Note
   also that no `ForwardedHeaders` plugin is installed, so behind a proxy the
   IP bucket is shared by all traffic.
6. **No integration test through the real controller.** Every bulk test injects
   a fake `PoiAvailabilitySliceLookup`; the assembled production path is
   untested. One test over the shared test DB with a few seeded POIs — including
   an unknown id and one whose provider throws — would cover it deterministically.
7. **`internal_error`** is a new wire code, absent from this spec's vocabulary
   and from the route's OpenAPI description.
8. **Minor cleanups:** the `rejects an unparseable date` test is now tautological
   (the mandatory-`end_date` check fires first, leaving `parseDate`'s failure
   path uncovered); `MAX_CAUSE_DEPTH` now exists in three files; a stale
   "spec Decision 5" citation in `BulkAvailabilityRoutes.kt`; untested
   `tolerance`/`ipRateLimitPerMinute` validation; `tolerance` permits zero,
   which means "refetch every POI every request".
