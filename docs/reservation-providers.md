# Availability providers

Campsite availability and watches are dispatched through one abstraction:
`AvailabilityProvider`. Every upstream booking or availability system (rec.gov,
Aspira NextGen, ReserveAmerica, future regional vendors) is an adapter behind
this port. Routes never call this port directly. Availability services resolve
a POI or reservable into provider-ready targets, then call the adapter.

## Why an abstraction

The dispatch logic used to live in parallel paths (single-id availability and
watch polling), each parsing `provider_ref` JSON inline and importing
per-provider helper functions. Adding a third provider meant editing several
files; forgetting one was a silent bug. The current shape collapses that into
one registry lookup behind `AvailabilityTargetResolver`.

This doc is the contract. **A new availability provider is a new file under
`service/availability/provider/adapters/<vendor>/` and one row in the registry;
nothing else outside that directory should change.** That rule is the test of
whether the abstraction is right.

For the implementation checklist, use
[adding-a-reservation-provider.md](adding-a-reservation-provider.md). This file
explains the abstraction; the onboarding doc walks through the code, config,
tests, and operational wiring for a new provider.

## Layout

```
service/availability/provider/
├── AvailabilityClient.kt           # normalized availability operations
├── AvailabilityProviderClients.kt   # boot-time vendor client set + lifecycle
├── AvailabilityProvider.kt          # availability + provider metadata port
├── AvailabilityProviderId.kt        # enum/provider identity
├── AvailabilityProviderRegistry.kt  # forPoi(row, ref) → adapter that can handle it
├── AvailabilityProviderCapabilities.kt
├── ProviderRefParser.kt            # JSONB → models.ProviderRef (single source)
└── adapters/
    ├── recgov/                 # availability + watches
    ├── campflare/              # availability
    ├── aspira/                 # availability
    ├── reserveamerica/         # availability
    └── reservecalifornia/      # availability
```

`models.ProviderRef` (sealed class with `RecGov` / `Campflare` / `Aspira` /
`ReserveAmerica` / `ReserveCalifornia` variants) is the wire shape. Adapters take a `ProviderRef` of their
matching variant and the registry guarantees the dispatch is correct.

Every vendor adapter class implements both `AvailabilityClient` and
`AvailabilityProvider`: `AvailabilityClient` is the shared normalized
availability contract, while `AvailabilityProvider` adds identity,
capabilities, ref handling, and booking-link metadata. Raw HTTP clients under
`clients/` stay vendor-specific because their upstream request and response
shapes are genuinely different. The adapter boundary is where those shapes
become provider-neutral `AvailabilityObservationBatch` values.

The registry does not hardcode fallback modes. Availability services enumerate
candidate provider refs from the catalog/registry, and the registry asks the
mapped provider whether it `canHandle(ref)`. If one provider declines a ref
because it is unconfigured in this process, the resolver continues to the next
linked candidate ref. This is how a Campflare catalog row can naturally fall
through to a linked rec.gov alias without a Campflare-specific service branch.

Boot wiring passes those vendor-specific HTTP clients as one
`AvailabilityProviderClients` set. Every vendor client interface is
`AutoCloseable` with a default no-op close; implementations that actually own
closeable resources, such as RecGov's Ktor client, override it. `Main` closes
the set, not an individual vendor, so transport lifecycle does not leak through
the availability-provider abstraction.

The availability orchestration that consumes this port lives one layer above:

```
service/availability/
├── AvailabilityService.kt               # POI availability contract used by routes
├── ReservableAvailabilityComposer.kt    # grouping, window policy, per-collection availability load
├── AvailabilityTargetResolver.kt        # reservable → parent provider + date context
└── AvailabilityDateResolver.kt          # target-local earliest date/window policy
```

## Capabilities

Not every provider supports every monitoring action. The capability flags
on each provider drive what the FE shows.

```kotlin
data class AvailabilityProviderCapabilities(
    /** Can we serve per-day availability for a window? */
    val supportsAvailability: Boolean,
    /** Can we poll for openings and notify on match? */
    val supportsAlerts: Boolean,
    /** Max days into the future the upstream exposes. */
    val bookingHorizonDays: Int,
)
```

The API can surface this struct for the campground behind a POI so the
drawer can hide affordances the provider doesn't support.

## Supported monitoring actions

| Action | Required interface | Notes |
|---|---|---|
| Per-day availability for a window | `AvailabilityClient.availability(ref, startDate, endDate)` | Drives provider-level availability. Adapters fetch upstream directly; the decision to serve stored data or call the adapter live is handled above it by `AvailabilityLoader`, reading current state from the `availability` interval table. |
| Catalog availability for linked campsites | `AvailabilityClient.catalogAvailability(ref, campsites, startDate, endDate)` | The POI/campsite catalog path uses this so returned availability is narrowed to known catalog rows. |
| Capability probe | `AvailabilityProvider.capabilities` | Static per adapter; cheap. |
| Watch evaluation on poll | watch evaluator | `same_site` requires one site bookable across all N nights; `any_combination` succeeds if at least one site is open per night. |
| Record availability history | poller writes status-run rows to the `availability` interval table | Provider-agnostic; uses `AvailabilityObservationBatch` observations. |
| Notify on match | poller dispatches via Slack (`slack_notify`; push future) | Channels are not provider-specific. See `docs/superpowers/specs/2026-07-03-availability-alerts-design.md`. |

Availability providers do not model cart automation, payment, or booking on
the user's behalf. Watch flows produce matches, notifications, and
availability history only.

## Today's adapter matrix

| Provider | Availability | Watches | Notes |
|---|---|---|---|
| RecGov (rec.gov) | ✓ | ✓ | Availability and generic watch polling. |
| Campflare | ✓ | ✗ | Availability uses v2 bulk campground availability for Campflare-owned US catalog rows. Alerts stay off until cadence/load limits are validated. |
| Aspira NextGen (BC Parks, Washington, Pennsylvania) | ✓ | planned | Availability ships now; watch dispatch still needs work. |
| ReserveAmerica / Active Network (Alberta Parks, New York State Parks) | ✓ | ✗ | Availability reads the live campsite-calendar matrix; sites are cataloged from that same calendar roster (see `reserveamerica.md`). Alerts stay off until upstream cadence/load limits are validated. |
| ReserveCalifornia / Tyler | ✓ | ✗ | Availability reads standard facility grids. Catalog import uses the public Search All Parks `search/place` flow. |

When a row is added here, it should match a real file in
`service/availability/provider/adapters/<vendor>/`. If the table promises a
capability the adapter doesn't implement, that's a doc bug; fix the doc, not the
adapter.

## Polling is watch-driven

The poller does **not** scrape on a schedule. The unit of work is a
`(poi_id, target_date)` slot, and a slot is polled if and only if at
least one active watch covers it. Polling starts on the first watch
covering a slot, stops when the count hits zero, and stops
unconditionally when the date elapses.

This shape gives us three properties that hold regardless of UI changes:

- **Bounded upstream load.** No "popular campgrounds" list to maintain;
  no debate over what to scrape proactively. The user expresses interest
  by setting a watch, and that's the input to the poller.
- **Free dedup across users.** Two users watching the same slot share
  one poll. Adding more watch-driven features doesn't multiply polling cost.
- **Natural stop conditions.** No janitor process required to garbage-
  collect stale polls. The slot table mirrors the watch table; both
  shrink together.

Adapters do not own polling cadence — the platform poller does. The poller
uses `AvailabilityTargetResolver` to resolve the same provider target as live
availability, then calls the provider through the same availability port.
Cadence, backoff, dedup, and the "should we poll right now" decision all live
above the adapter, inside the generic poller.

### Cadence is layered config, not a constant

Cadence resolves through a fall-through chain:

```
alert override  →  campground override  →  global default
```

The principle: **different campgrounds have different cancellation
dynamics, and the poller has to be configurable per-target without
touching code.** Upper Pines in Yosemite re-snaps cancellations within
seconds; a regional state park may stay open for hours. A hardcoded
cadence is wrong for both.

What the platform owns:

- The fall-through resolver. Adapters never see "what's my cadence" —
  they're called when the poller decides it's time.
- The reconciliation between the configured cadence and upstream
  health. Rate limits, exponential backoff on failure, and adapter-
  level throttles all override the resolver. Cadence is a *target*,
  not a guarantee.

What's deferred (see RFC 0007): the actual override columns and the
admin UI to set them. v1 ships the resolver with global config only;
overrides plug in later without changing call sites.

## How a watch becomes API calls

Every poll run leaves a trace of the upstream calls it actually made, one
level below the `availability_job_run` row described above:

```
watch → job → run → fetch-call(s) → snapshots
                     └ one availability_fetch_call row per upstream group call
```

A run can cover many reservables (a POI-scope watch fans out to every child
reservable), but the poller does not issue one upstream call per reservable.
`CatalogAvailabilityBatcher` groups a run's resolved targets by
`(provider, parentRef, dateContext)` and issues exactly one
`catalogAvailability` call per group — so N reservables under one campground
become one upstream call. This is what fixed the old per-site rate-limit
fan-out. Call-shaping stays inside each adapter (months for rec.gov, the park
matrix for ReserveAmerica, per-day map calls for Aspira); the batcher and poller
never branch on vendor, they only group and dispatch.

`availability_fetch_call` is the trace table for this grouping: one row per
group call, keyed by `run_id`, with columns `provider`, `parent_ref`,
`reservable_count`, `window_start` / `window_end`, `outcome`
(`ok | rate_limited | upstream_5xx | blocked | other`), `duration_ms`, and
`error`. Rows are written only when a real upstream call was made — a group
with no future dates is skipped by the batcher and produces no row. This
table is surfaced in the "Reservable Availability Watch drill down" Grafana
dashboard's "Fetch calls for this run" panel, with a "Rate-limited fetches"
monitor watching the outcome column across runs.

For deep debugging beyond the trace row, `run_id` is set in the logging MDC
for the duration of the fetch, so the rec.gov client's `Poller: GET …` and
`429 …` log lines carry `run_id` and can be correlated back to the run and
the fetch-call row it produced.

## Availability history

History is a side effect of the watch poller, not a separate ETL, and it is
not a separate table. Each `(campsite_id, target_date)` cell has a chain of
status-run rows in the `availability` interval table: an unchanged poll bumps
the current row's `last_observed_at` in place, and a status change inserts a
new row linked to its predecessor by `previous_id`. History is that chain —
walk `previous_id` back from the current row. Each row is an interval
`[previous.last_observed_at, last_observed_at]`. Two principles:

- **History only exists for slots we polled.** No background backfill,
  no synthetic data. If a slot was never alerted on, there's no history
  for it. Capability-gate any history endpoint behind
  `supportsAlerts`.
- **Widen data per upstream call.** Upstreams return a window of
  per-day availability in one response. Record the whole window, not
  just the alerted slot. Same upstream cost; vastly more history.

History is read through provider-agnostic SQL on the `availability` table.
Adapters do not own history queries. The lingua franca is one
reservable/date/status observation with the shared status enum:
`first_come | reserved | available | closed | unknown`. `first_come` is
visible to users but does not count as online availability; missing provider
data is `unknown`, not `closed`. Provider-specific richness (equipment-type
breakdowns, upstream map structure) is *not* captured; that
fidelity lives in the live availability call. The interval rows are the
long-tail summary, not a replay log.

## Lifecycle: how a user's intent becomes a watch

```
Drawer (this product)              Poller (background)             Watches UI
─────────────────────             ──────────────────               ──────────────────
browse → pin click
  ↓
GET /api/pois/{id}
  ↓
GET /api/poi/{poi_id}/reservables/availability
  ↓                                AvailabilityService
week pages                         ↓
  ↓                                AvailabilityTargetResolver
  ↓                                AvailabilityProvider.catalogAvailability
                                   ↓
                                   watch evaluator
  ↓                                  ↓ (match)
"Set watch" click                  notify (Slack / push)
  ↓                                  ↓
POST /api/availability/watches     record availability status-runs
                                                                   list watches, pause,
                                                                   per-watch history,
                                                                   tune notification
                                                                   channel
```

The drawer captures **intent only**. The poller is the only thing that
produces matches and snapshots. The watches UI surfaces everything the
poller has produced.

## Adding a new availability provider

1. Add a row to `AvailabilityProviderId` (enum) if this is a new upstream
   platform.
2. Add a `ProviderRef.<Vendor>` variant if the wire shape isn't already
   covered.
3. Create `service/availability/provider/adapters/<vendor>/<Vendor>AvailabilityProvider.kt`
   implementing `AvailabilityProvider`. Capabilities default conservatively
   (`supportsAlerts = false`); flip them on as features land.
4. Ensure the terminal ETL emits the right `provider_ref` JSON and that its
   `pois.source` maps to the adapter in `AvailabilityProviderRegistryFactory`.
5. Update the matrix table above.

Steps 1–5 should be the entire provider-registration diff. If you find
yourself editing route files, `AvailabilityServiceImpl`, or the watch poller
core only to branch on a new vendor, the abstraction is leaking — fix that
before merging.

## Per-vendor API docs

Each adapter's upstream API is documented separately under
`docs/reservation-providers/`:

- [aspira.md](reservation-providers/aspira.md) — Aspira NextGen
  (`reservation.pc.gc.ca`, `camping.bcparks.ca`,
  `washington.goingtocamp.com`).
- [campflare.md](reservation-providers/campflare.md) — Campflare v2 bulk
  campground availability.
- [reservecalifornia.md](reservation-providers/reservecalifornia.md) —
  ReserveCalifornia / Tyler Technologies.
- [reserveamerica.md](reservation-providers/reserveamerica.md) — ReserveAmerica /
  Active Network (`shop.albertaparks.ca`, `newyorkstateparks.reserveamerica.com`).
- _recgov.md — to be written._

When adding a new vendor, follow the
[probe-vendor-api skill](../.claude/skills/probe-vendor-api/SKILL.md)
to capture the wire shape, then write `reservation-providers/<vendor>.md`
using `aspira.md` as the template. **Do not inline vendor wire
shapes in this doc** — this doc owns the architecture contract;
per-vendor docs own the wire details.

## See also

- [backend-architecture.md](backend-architecture.md) — overall layer
  rules. Adapters live under `service/availability/provider/`; routes consume
  availability services, not provider adapters or the provider registry.
- `rfcs/0007-availability-search-and-alerts.md` — the RFC that introduced
  this abstraction and the monitoring lifecycle it enables.
- [.claude/skills/probe-vendor-api/SKILL.md](../.claude/skills/probe-vendor-api/SKILL.md)
  — methodology for reverse-engineering a new reservation vendor's API.
