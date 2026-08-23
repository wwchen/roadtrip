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
- **Windows are not silently clamped.** `AvailabilityDateResolver.resolveWindow`
  *throws* `BadDateWindow.WindowTooLong` / `BeyondBookingHorizon` rather than
  narrowing. With explicit dates, every POI that succeeds returns exactly the
  requested window, so runs are directly comparable across POIs. The one
  per-POI variance is `earliestDate`, derived from the POI centroid's timezone
  plus an 18:00 cutoff, which can make one POI reject a start date another
  accepts.
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
standard than one evaluated early. The bulk controller computes the cutoff once
and every POI is measured against the same line. It also unifies the read path
with the poller and with the SQL predicate that already takes `freshAtOrAfter`.

No clamp or floor is built: callers are internal, trusted code, and vendor-load
protection already lives in `VendorRateLimiter` and `ProviderCooldownTracker`.

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
- **Ranking POIs against each other.** The caller sorts. Runs are comparable
  across POIs (see Current state), so this is a caller-side sort.
- **Reporting every qualifying run window per site**, not just the longest.
  Additive to the DTO later if the planner needs specific stay windows.

## Risks

- **Latency is bounded by the slowest POI**, up to `per-poi-timeout`. This is
  the accepted cost of a synchronous contract and the main thing to measure
  before considering the deferred design above.
- **A cold scan of N POIs is up to N vendor calls.** Mitigated by the existing
  snapshot cache, by `VendorRateLimiter` and `ProviderCooldownTracker`, by
  `max-pois`, and — for Campflare — by P2. Worth watching in the
  `availability_fetch_call` Grafana panels after launch.
- **The `poiAvailabilitySlice` extraction touches the live detail endpoint.**
  Behavior parity via the existing tests is the gate.
