# One availability check: the grid, the stay check and the pollers share one fetch

**Status:** draft written 2026-11-07 from the code as it stands at `06d74a00`; the owner settled
its three open questions the same day (below). This document awaits review.

**Amended 2026-11-07 (owner's decisions), replacing the text below where they differ:**

- **Watches, settings, sign-in and add-to-cart stay unreachable on `roadtrip.floo.ca`** until
  sign-in is ported to Vercel. No proxy and no separate Kotlin host.
- **One vendor rate-limit bucket per provider, with a raised capacity.** Readers and pollers share
  it; the capacity is configuration, not a second bucket.
- **No grouped variant of the check.** A poll already makes one vendor call per campground:
  `CatalogAvailabilityBatcher` groups by `(provider, campground, date context)`. Whether a provider
  answers several campsites or campgrounds in one upstream call (Campflare's bulk endpoint, for
  example) is that provider client's concern, behind `AvailabilityProvider`. The poll executor
  calls the check once per campground in its poller.

**Parent specs:** `docs/superpowers/specs/2026-11-05-availability-egress-design.md` (D2 one check
primitive, D4 Vercel calls mini-ca, D5 the queue was left for later) and
`docs/superpowers/specs/2026-10-27-kotlin-to-typescript-backend-design.md` (C1 residential
egress, C3 one rate-limit authority).

## Problem

The egress spec decided that one check serves every surface. The code has three paths, and two of
the differences are bugs rather than style.

1. **Alerts are lost when a reader sees an opening first.** `AvailabilityRepo.recordObservations`
   returns a `CellTransition` only for a cell whose status changed. The read-through
   (`AvailabilityLoader.recordFetched`) calls it with `runId = null` and drops the result. When
   the grid, the stay check or an egress refresh is first to see a campsite open, that status
   becomes current; the poller's next write sees no change and `WatchAlertDispatcher.dispatch`
   never fires. Every Vercel refresh is now such a reader.
2. **Only the pollers are rate limited.** `VendorRateLimiter` (Postgres-backed Bucket4j, 60 per
   minute) has one consumer, `AvailabilityPollExecutor`. The grid, the stay check and the egress
   refresh call `FailoverAvailabilityFetcher` without it. The egress spec (D6) and
   `docs/web-app.md` both say the refresh goes "through the one rate limiter"; it does not.
3. **The stay check has no handler on Vercel.** `roadtrip.floo.ca` now points at the Vercel
   production deployment, and `apps/web` has no `POST /api/pois/availability/stays`. The map's
   Check button posts to a route that does not exist there.
4. **The pollers fetch by a different route.** `AvailabilityPollExecutor` goes
   `DbAvailabilityTargetResolver` → `CatalogAvailabilityBatcher` → `FailoverAvailabilityFetcher`,
   with its own freshness test (`hasFreshCoverage` over the cadence), its own window
   (`resolvePollingWindow`) and no UNKNOWN back-fill. The read-through uses the provider TTL,
   target plus wide fetch window, and back-fills UNKNOWN for its targets.

## Goal

Every vendor availability fetch on mini-ca goes through one Kotlin primitive that resolves the
serving provider and window, decides freshness, charges the one vendor rate limiter, fetches with
failover, records the cells, and hands every status change to the watch dispatcher. The stay check
runs on Vercel through the egress endpoint, on the same primitive.

Success: a campsite that opens is alerted on whichever caller saw it first; no vendor call leaves
mini-ca without charging `VendorRateLimiter`; the map's Check button works on
`roadtrip.floo.ca` with the same streamed contract it uses today.

## Where this sits

This replaces spec 2 of the egress spec's three ("scheduled polls and watches on the same check,
using Vercel Queues and Workflow"). The scheduled polls stay on mini-ca (D2 below), so no queue is
needed. Watch management on Vercel needs sign-in, which is step 8 of the parent TypeScript spec;
this spec leaves it gated and unreachable from `roadtrip.floo.ca` until then (D5).

## Non-goals

- Porting sign-in, watch CRUD, settings, add-to-cart or the Slack interactivity route to Vercel.
- Porting vendor clients, the scheduler or notifications to TypeScript.
- Vercel Queues and Workflow. Nothing in this spec needs them.
- The per-cell cache top-up deferred by the stay-check contract spec.
- Changing the stay-check wire contract (`StayCheckRequestDto`, `StayAnswerDto`,
  `StayCheckDoneDto`).

## Decisions

| # | Decision | Why |
|---|---|---|
| D1 | **One Kotlin primitive, `AvailabilityCheck`, owns every vendor fetch** on mini-ca: the grid read-through, the stay check, the egress refresh and the pollers | The egress spec's D2. Four callers of one function cannot drift on freshness, window, limiter or recording. |
| D2 | **Scheduled polls stay in mini-ca's in-process `Scheduler`** | C1 puts vendor egress on mini-ca anyway, and egress D4 found mini-ca cannot pull a Vercel Queue without a ~12-hour development OIDC token. A queue would move work from mini-ca to mini-ca. |
| D3 | **Recording returns transitions, and the primitive dispatches them**, whatever the caller | Fixes the lost alert (Problem 1). The poller already dispatches; the read-through starts to. |
| D4 | **Every fetch charges `VendorRateLimiter`**, one bucket per provider shared by readers and pollers, capacity raised | Makes C3 true. Owner's choice over separate reader and poller buckets. A starved request-path call answers from the store with `rate_limited` instead of calling the vendor. |
| D5 | **Watches stay on Kotlin and gated on Vercel**, unreachable from `roadtrip.floo.ca` until sign-in is ported | Owner's choice over proxying those routes to Kotlin. Watch CRUD is `User` or magic-link access; Vercel has no sign-in until the auth step of the parent spec. Existing watches keep polling and alerting on mini-ca. |
| D6 | **The stay check moves to Vercel** as `POST /api/pois/availability/stays` in `apps/web`, same SSE contract, generated from the same DTOs | It is anonymous, so it needs no sign-in, and it is a fan-out of the check the egress endpoint already serves. |
| D7 | **The egress `refresh` mode takes an optional `fresh_at_or_after`** | The stay check's `max_age_seconds` becomes that cutoff; without it, `refresh` keeps the provider TTL. One mode, one freshness rule. |
| D8 | **Vercel computes the stay summary** from the egress cells and Neon's campsites | `StaySummary.staySummary()` and `fittingGroup` are small pure functions over cells and `max_people`, which Neon holds. Porting them keeps the egress contract a cell list. |

## Architecture

```
 map Check button                     matrix page                 mini-ca Scheduler
       │ POST /api/pois/availability/stays   │ GET + POST …/refresh          │ every 5 s
       ▼                                     ▼                               │
 Vercel apps/web ── egress {mode: refresh, fresh_at_or_after} ──┐            │
                                                                ▼            ▼
                                   mini-ca Kotlin  AvailabilityCheck.check(target, window, freshness, caller)
                                                    │ serving provider + window (BookingHorizonResolver)
                                                    │ fresh in store? → answer from store
                                                    │ VendorRateLimiter.tryAcquire → starved? answer stored, rate_limited
                                                    │ FailoverAvailabilityFetcher.fetch
                                                    │ AvailabilityRecorder.record(runId?, observations) → transitions
                                                    └ WatchAlertDispatcher.dispatch(live watches, transitions)
```

### `AvailabilityCheck` (Kotlin, `service/availability/`)

One method:

```kotlin
suspend fun check(request: CheckRequest): CheckResult
```

- `CheckRequest`: the campground, the campsites to answer for, the requested window (or none for
  the default week), a `Freshness` (`ProviderTtl`, `AtOrAfter(Instant)`, or `Cadence(Duration)`
  for pollers), and a `CheckCaller` (`Grid`, `StayCheck`, `Egress`, `Poller(pollerId)`) used for
  the run row and logs.
- `CheckResult`: the resolved window, the latest bookable date, the observation batch, whether it
  came from the store, and `rateLimited` when the limiter refused a fetch.

It is built from what exists: `CampsiteAvailabilityService.resolveTargetWindow` for the window,
`AvailabilityLoader`'s per-cell freshness for `ProviderTtl` and `AtOrAfter`, `hasFreshCoverage`
for `Cadence`, `VendorRateLimiter` for the charge, `FailoverAvailabilityFetcher` for the fetch.
`CampsiteAvailabilityService.fetchAvailability`, `AvailabilityLoader.loadOrFetch` and the
fetch half of `AvailabilityPollExecutor` become callers of it, not parallel implementations.

### `AvailabilityRecorder`

`recordObservations` already returns transitions. The recorder wraps it: it writes the batch
(with the poller's `runId`, or none for readers), applies the UNKNOWN back-fill for the request's
own targets, and passes the bookable transitions to `WatchAlertDispatcher.dispatch` with the
watches live on those campsites. The dispatcher's scope and window filters, and its
`stopWhenTriggered` handling, are unchanged.

Concurrent writers: two callers recording the same opening at once can both see the old status
and both insert. `insertStatusRuns` must take a row lock per cell (or rely on a unique
`(campsite_id, target_date, previous_id)` run constraint) so exactly one transition is emitted.
The existing poller has the same race with the read-through today; this spec closes it because it
makes both paths dispatch.

### Pollers

`AvailabilityPollExecutor.handle` keeps its claim, lease, run bookkeeping and back-off, and calls
`AvailabilityCheck.check` once per campground in its poller, with `Freshness.Cadence(cadenceSec)`
and `CheckCaller.Poller`. The polling window (`resolvePollingWindow`) is passed as the requested
window. `CatalogAvailabilityBatcher` already fetches per `(provider, campground, date context)`,
so moving the fetch into the check changes no vendor call count; its grouping and outcome
mapping move into the executor's loop and the batcher goes. Upstream bulk fetching stays inside
each provider client.

### Egress contract change

`AvailabilityEgressRequestDto` gains `fresh_at_or_after: String?` (an ISO instant), honoured only
in `refresh` mode, and `AvailabilityEgressResponseDto` gains `rate_limited: Boolean = false`.
`make api-types` regenerates the TypeScript. Nothing else in the contract changes.

### Stay check on Vercel

`apps/web/src/app/api/pois/availability/stays/route.ts`, with logic in
`apps/web/src/server/service/availability/stayCheckService.ts`:

- Validates as Kotlin does (the `validated` extension used in `StayCheckRoutes.kt`): at most 50 POIs, `max_age_seconds`
  clamped to 60 s–2 h, a valid stay window.
- For each POI, in request order, at most 3 at a time: loads the campground and campsites from
  Neon (`weekTarget`), calls the egress with `mode: "refresh"` and
  `fresh_at_or_after = now − max_age`, then computes `StaySummaryDto` from the cells over the
  campsites that fit `group_size`.
- Streams `answer` events and a final `done` event as SSE, with a 15 s heartbeat, a 45 s per-POI
  timeout and a 120 s stream deadline; `maxDuration` 150.
- Maps egress failures to `StayFailureReason` as `StayFailureClassifier` does (400 window reasons,
  404 `not_found`, 501 `unknown_campground`, 503 `rate_limited`/upstream codes, timeout
  `timed_out`), and `rate_limited: true` answers to `refused` with reason `rate_limited`.
- Per-IP limiting is a Vercel Firewall rule on the path, matching Kotlin's 150 POIs per minute,
  as for the refresh POST.

The frontend does not change: `streamStays` already posts to this path and parses this stream.

## Configuration

| Setting | Where | Default |
|---|---|---|
| `STAY_CHECK_MAX_POIS`, `STAY_CHECK_FAN_OUT`, `STAY_CHECK_PER_POI_TIMEOUT_MS`, `STAY_CHECK_STREAM_TIMEOUT_MS` | Vercel, optional | 50, 3, 45000, 120000 (Kotlin's `StayCheckConfig`) |
| `VendorRateLimitConfig` capacity and refill | mini-ca | 60 and 60 per minute today; raised in step 2, sized from the poll and reader call counts in `availability_fetch_call` and the egress logs before it ships |

The stay check is on wherever the egress is configured, like the matrix; unset, it answers every
POI `failed` with `provider_misconfigured`.

## Failure handling

- mini-ca unreachable: the matrix GET stays `503`; each stay answer is `failed`,
  `upstream_unreachable`, retryable.
- Limiter starved: the matrix shows the stored week (the refresh POST's result is just not
  fresher); the stay check answers from the store when it is fresh enough, else `refused`,
  `rate_limited`, retryable; a poller reschedules as today.
- Dispatch failure: logged and swallowed by the recorder, so a broken Slack token never fails a
  reader's request. The poller keeps its current behaviour.

## Testing

- Kotlin: `AvailabilityCheck` unit tests per freshness kind; a test that a grid read seeing an
  opening dispatches to a live watch (the regression for Problem 1); a test that every caller
  charges the limiter and a starved call makes no vendor call; a concurrency test that two
  recorders of one opening emit one transition.
- Egress route tests for `fresh_at_or_after` and `rate_limited`, with contract rows updated.
- Vercel: `stayCheckService` tests against the catalog fixture with a fake egress (fresh, stale,
  refused window, timeout, rate limited, group-size filter); route tests for validation, SSE
  framing, heartbeat and deadline.
- Smoke: the existing stay-check smoke runs against a preview with egress configured.

## Rollout

1. Kotlin: `AvailabilityRecorder` with dispatch, used by the read-through. Ships alone; it fixes
   lost alerts on mini-ca's current routes.
2. Kotlin: `AvailabilityCheck` with the limiter on every caller; the read-through and egress
   refresh move onto it.
3. Kotlin: the poll executor moves onto it; `CatalogAvailabilityBatcher` goes.
4. Egress contract: `fresh_at_or_after`, `rate_limited`; `make api-types`.
5. Vercel: the stays route, behind the existing egress configuration. Add the Firewall rule.
6. Docs: `docs/web-app.md`, `docs/reservation-providers.md` and `docs/backend-architecture.md`
   stop saying the refresh is rate limited before step 2 and say what is true after it.

Each step is its own PR; steps 1–3 need no Vercel change.

## Known limits

- Watches, settings, sign-in and add-to-cart remain unreachable from `roadtrip.floo.ca` while it
  points at Vercel. Magic links in alert emails point at `roadtrip.floo.ca` and fail there too.
- A busy map shares the vendor bucket with the pollers, so heavy reading can delay polls (and
  their alerts) until the bucket refills.
- The stay check waits on vendors (up to 45 s a POI), unlike the matrix's stale-now pattern; that
  is the existing contract.
- Every Vercel stay check costs one tunnel call per POI.

## Settled questions

- **Q1. How does `roadtrip.floo.ca` reach watches before sign-in is ported?** It does not (D5).
- **Q2. Limiter capacity once readers charge it.** One bucket per provider, raised (D4).
- **Q3. Grouped fetches for pollers.** Not needed: polls already fetch per campground, and bulk
  upstream calls are a provider client's concern (Pollers, above).
