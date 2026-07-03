# Availability fetch batching + trace — design

Date: 2026-07-02
Status: proposed
Related: `docs/reservation-providers.md`, `rfcs/0007-availability-search-and-alerts.md`

## Problem

The availability poller wedged in prod. For watch 1 / job 1 (POI 2006 =
Upper Pines, Yosemite), the last 500 runs were 499 failed / 1 started, 497
with `error = rate_limited`, 0 completed since ~2026-06-30. The
`reservable-watch-drill` Grafana dashboard faithfully reported the failures,
but there was no way to see *why* below the run row — the only detail lived in
ephemeral `docker logs`.

### Root cause

Two independent defects compound:

1. **Per-site fan-out (the volume bug).**
   [`AvailabilityPollExecutor.runPoi`](../../../backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt)
   loops over every child reservable and calls the single-site
   `ReservationProvider.reservableAvailability` through
   [`ReservableAvailabilityFetchService`](../../../backend/src/main/kotlin/ca/floo/roadtrip/service/api/ReservableAvailabilityFetchService.kt),
   which has no dedup. rec.gov availability is per-campground-month, so 235
   sites under one campground produce 235 identical
   `GET .../campground/232447/month?start_date=2026-07-01` calls per run.
   Prod logs confirmed 505 poller GETs in a 20-minute window, 100% to that one
   URL. rec.gov returns HTTP 429; the run aborts `rate_limited`.

2. **No failure backoff (the persistence bug).**
   [`AvailabilityPollExecutor.handle`](../../../backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt)
   returns `HandlerResult(nextRunAt = now + cadenceSec)` on **both** the success
   and `catch` paths. A rate-limited run reschedules at the flat 120 s cadence,
   so the poller hammers rec.gov again 2 minutes later and never escapes the
   penalty box.

### Key finding

The provider port **already** solves call-shaping.
`ReservationProvider.catalogAvailability(CatalogAvailabilityRequest)` takes a
*set* of reservables and lets each adapter decide the least-costly upstream
calls. `AvailabilityServiceImpl.getByRids` (the live availability path) already
groups reservables by `(provider, parentRef, dateContext)` and calls
`catalogAvailability` **once per group**. The poller is the only fetch path
that ignores this seam — it hand-rolls a worse, un-deduplicated copy.

The three (four) adapters have genuinely different call shapes, all fully
encapsulated behind `catalogAvailability`:

| Provider        | Call unit                       | `catalogAvailability` does                              |
|-----------------|---------------------------------|---------------------------------------------------------|
| rec.gov         | (campground, month)             | one `fetchMonth` per month in window, projects N sites  |
| ReserveAmerica  | (contractCode, parkId, window)  | one park-matrix `fetch` for the window, projects N rows |
| Aspira          | map (+ resourceLocation)        | `/api/occupancy` if single resourceLocationId else `/api/availability/map` |
| ReserveCalifornia | facility grid                 | facility grid read                                      |

Grouping by `parentRef` in the caller is correct for all of them because
`parentRef` **is** each vendor's call-unit. Anything finer (month chunking,
occupancy sub-strategy) is the adapter's job and already implemented.

## Goals

- One upstream call per campground/map per run instead of one per site.
- A rate-limited watch backs off instead of wedging.
- A durable, queryable **trace** of how a watch becomes API calls, surfaced in
  the existing Grafana + postgres tooling, plus a rate-limit monitor.
- No duplicated grouping/fetch logic across the live path and the poller.
- Call-shaping stays inside adapters; no vendor call-shape leaks upward.

## Non-goals

- Per-HTTP-call (retry-by-retry) durable rows (option B). Deferred; the design
  leaves a documented extension point.
- Cadence override columns / admin UI (RFC 0007 defers these).
- Any change to adapter upstream behavior or the `ReservationProvider` port
  surface. The fix is caller-side + observability.
- Log aggregation infra (Loki/Datadog). Debug detail stays in run-scoped logs.

## Architecture — one batched seam

Extract the grouping+fetch that `AvailabilityServiceImpl` already performs into
a shared collaborator; both callers depend on it. Delete the poller's per-site
loop.

```
CatalogAvailabilityBatcher (new, shared, above the port)
  in:  resolved targets + window policy + force flag
  step: group targets by (provider, parentRef, dateContext)
        for each group:
          call provider.catalogAvailability(ref = parentRef,
                                             reservables = group rows,
                                             window)                 [once]
          time it; classify outcome (ok | rate_limited | upstream_5xx | blocked)
  out: List<GroupFetchResult> {
         group, batch (on success) | error (on failure),
         outcome, upstreamAttempts?, durationMs
       }

AvailabilityServiceImpl   → batcher (cache-backed via SnapshotBackedAvailabilityService),
                            maps results → AvailabilityResponseDto  (behavior unchanged)
AvailabilityPollExecutor  → batcher (force = true),
                            maps results → availability_snapshot rows + trace rows (runId)
```

Design rules honored:

- **Dedup / single source.** Grouping, the `catalogAvailability` call, and
  outcome classification live in exactly one place. The batcher *computes*;
  each caller *persists* in its own context. Persistence is not forced into the
  shared unit, so the live path stays cache-backed and the poller stays
  force-fetch + snapshot-append without either duplicating the other.
- **No leaky abstraction.** The batcher and both callers operate on
  `(provider, parentRef, reservables[], window)`. Month chunking, occupancy vs
  map, contract codes — none of it surfaces above `catalogAvailability`.
- **Layering.** Batcher sits in `service/availability` (or `service/api`)
  above the port, below the routes. Adapters unchanged.

### Outcome classification

Outcome derives from the `ReservationProviderError` subtype the adapter already
throws (`RateLimited`, `UpstreamUnavailable`, `UpstreamBlocked`, …), mapped to a
small platform enum: `ok | rate_limited | upstream_5xx | blocked | other`. This
is the same information the run row's `error` string carries today, promoted to
a typed, per-group value.

## Trace (option A — group level)

New table `availability_fetch_call`, one row per group call, written by the
poller from each `GroupFetchResult`, keyed by `run_id`:

| Column            | Notes                                             |
|-------------------|---------------------------------------------------|
| `id`              | pk                                                |
| `run_id`          | FK → `availability_job_run.id`                    |
| `provider`        | `recgov` / `aspira` / `reserveamerica` / …        |
| `parent_ref`      | campground id / map id / park id (text)           |
| `reservable_count`| sites/resources covered by this one call          |
| `window_start`    | date                                              |
| `window_end`      | date                                              |
| `outcome`         | `ok | rate_limited | upstream_5xx | blocked | other` |
| `attempts`        | nullable; retry count if the adapter reports it   |
| `duration_ms`     | int                                               |
| `error`           | nullable text                                     |
| `created_at`      | timestamptz                                        |

This yields the trace the user asked for: *watch 1 → job 1 → run 796 → 1 call
to recgov `232447`, 235 reservables, Jul 17–31, `rate_limited`, 240 s.*

- **Correctness first** (per project convention): the row is written from the
  same result the poller uses to append snapshots, so trace and history can't
  diverge.
- The **live API path has no run**, so it writes no trace rows — its result
  sink is a no-op. This is the documented seam where option B's per-retry rows
  would plug in later without changing call sites.
- `run_id` is added to the logging MDC so the existing
  `Poller: GET …` / `429 rate limit …` lines are correlatable to a run for
  deep dives, covering the retry-by-retry detail that group rows omit.

## Backoff

Platform-layer, in `AvailabilityPollExecutor` (docs already assign rate-limit
backoff above the port):

- On `ok`: `next_run_at = now + cadenceSec` (unchanged); reset any backoff state.
- On `rate_limited` / failure: `next_run_at = now + min(cadenceSec * 2^consecutiveFailures, BACKOFF_CEILING)`.
- `consecutiveFailures` tracked per job (new column on `availability_job`, or
  derived from the trailing run statuses — decided in the plan).
- Named constants / config only — `BACKOFF_CEILING`, base multiplier — no inline
  magic numbers (per project design rules). Tunable via config where it should
  differ across environments.

## Surfacing

- **Grafana** `reservable-availability-watch-drill-down.json`: add a "Fetch
  calls for this run" table panel reading `availability_fetch_call` filtered by
  the existing `run_id` drill var. Reuses the current `roadtrip-postgres`
  datasource and the `IN ('', '__all')` guard pattern.
- **Rate-limit monitor**: a panel/alert query
  `count(*) FILTER (WHERE outcome = 'rate_limited') by provider, parent_ref over 1h`
  so a wedged watch is visible without reading logs.

## Docs

- `docs/reservation-providers.md`: add a "How a watch becomes API calls"
  section documenting the grouping/trace model and the
  `availability_fetch_call` table; document backoff as the platform rate-limit
  response (the doc already promises "exponential backoff on failure … override
  the resolver").

## Testing

- POI watch over N sites in one campground → batcher issues **1**
  `catalogAvailability` call, not N (regression test for the volume bug).
- POI watch spanning two campgrounds → exactly 2 calls (grouping correctness).
- A `rate_limited` group result → run recorded failed, one
  `availability_fetch_call` row with `outcome = rate_limited`, and
  `next_run_at` advanced by backoff (not flat cadence).
- Backoff resets after a successful run.
- Live path (`getByRids`) behavior unchanged after the batcher extraction
  (existing tests must stay green).

## PR sequencing

Per the multi-PR title convention, three PRs:

- **PR 1: batcher extraction + poller realign** — introduces
  `CatalogAvailabilityBatcher`, routes `AvailabilityServiceImpl` and the poller
  through it, deletes the per-site loop. Fixes the prod volume bug. No schema
  change.
- **PR 2: fetch-call trace** — `availability_fetch_call` migration, poller
  writes rows, MDC `run_id`, Grafana panel + rate-limit monitor.
- **PR 3: failure backoff** — backoff on `next_run_at`, state tracking, config
  constants, tests.

PR 1 is the prod fix and can ship alone. PR 3 is independent of PR 2. As an
immediate operational mitigation before PR 1 lands, job 1 can be paused or its
cadence raised (out of band; not part of this spec).

## Risks / open questions

- **Backoff state location** — new column vs derived from trailing run statuses.
  Resolve in the plan; leaning on a `consecutive_failures` column for an O(1)
  read.
- **Cache-hit trace semantics (live path)** — the live path writes no trace
  rows, so cache hits are simply invisible to the trace. Acceptable: the trace
  is a poll-run artifact, not a live-request log.
- **`parent_ref` shape** — stored as text; for Aspira the map id, for
  ReserveAmerica the park id, for rec.gov the campground id. Kept opaque and
  provider-scoped; not parsed by the dashboard.
