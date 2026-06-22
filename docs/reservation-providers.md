# Reservation providers

Campsite availability and watches are dispatched through one abstraction:
`ReservationProvider`. Every upstream reservation system (rec.gov, Aspira
NextGen, ReserveAmerica, future regional vendors) is an adapter behind this port.
Routes never call this port directly. Availability services resolve a POI or
reservable into provider-ready targets, then call the adapter.

## Why an abstraction

The dispatch logic used to live in parallel paths (single-id availability and
watch polling), each parsing `provider_ref` JSON inline and importing
per-provider helper functions. Adding a third provider meant editing several
files; forgetting one was a silent bug. The current shape collapses that into
one registry lookup behind `AvailabilityTargetResolver`.

This doc is the contract. **A new reservation provider is a new file under
`service/reservation/adapters/<vendor>/` and one row in the registry — nothing
else outside that directory should change.** That rule is the test of
whether the abstraction is right.

## Layout

```
service/reservation/
├── ReservationProvider.kt          # availability port (mandatory)
├── ReservationProviderId.kt        # enum/provider identity
├── ReservationProviderRegistry.kt  # forPoi(row) → adapter
├── ReservationProviderCapabilities.kt
├── ProviderRefParser.kt            # JSONB → models.ProviderRef (single source)
└── adapters/
    ├── recgov/                 # availability
    ├── aspira/                 # availability
    └── reserveamerica/         # availability
```

`models.ProviderRef` (sealed class with `RecGov` / `Aspira` / `ReserveAmerica`
variants) is the wire shape. Adapters take a `ProviderRef` of their
matching variant and the registry guarantees the dispatch is correct.

The availability orchestration that consumes this port lives one layer above:

```
service/availability/
├── AvailabilityService.kt          # rid/rids availability contract
├── AvailabilityServiceImpl.kt      # grouping, window policy, snapshot fetch
├── AvailabilityQueryService.kt     # POI/bulk query contract used by routes
├── AvailabilityTargetResolver.kt   # reservable → parent provider + date context
└── AvailabilityDateResolver.kt     # target-local earliest date/window policy
```

## Capabilities

Not every provider supports every monitoring action. The capability flags
on each provider drive what the FE shows.

```kotlin
data class ReservationProviderCapabilities(
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
| Per-day availability for a window | `ReservationProvider.availability(AvailabilityRequest)` | Drives provider-level availability. Per-month cache lives in the adapter. |
| Catalog availability for linked reservables | `ReservationProvider.catalogAvailability(CatalogAvailabilityRequest)` | POI/rids path uses this so the returned availability is narrowed to known catalog rows. |
| Reservable availability | `ReservationProvider.reservableAvailability(ReservableAvailabilityRequest)` | Narrow projection for a single reservable when the adapter supports it. |
| Capability probe | `ReservationProvider.capabilities` | Static per adapter; cheap. |
| Watch evaluation on poll | watch evaluator | `same_site` requires one site bookable across all N nights; `any_combination` succeeds if at least one site is open per night. |
| Append history snapshot | poller writes `availability_snapshot` rows | Provider-agnostic; uses `AvailabilityObservationBatch` observations. |
| Notify on match | poller dispatches via Slack / push (future) | Channels are not provider-specific. |

Reservation providers do not model cart automation, payment, or booking on
the user's behalf. Watch flows produce matches, notifications, and
availability history only.

## Today's adapter matrix

| Provider | Availability | Watches | Notes |
|---|---|---|---|
| RecGov (rec.gov) | ✓ | ✓ | Availability and generic watch polling. |
| Aspira NextGen (BC Parks, Washington, Pennsylvania) | ✓ | planned | Availability ships now; watch dispatch still needs work. |
| ReserveAmerica / Active Network (Alberta Parks, New York State Parks) | ✓ | ✗ | Availability reads the live campsite-calendar matrix. Alerts stay off until upstream cadence/load limits are validated. |

When a row is added here, it should match a real file in
`service/reservation/adapters/<vendor>/`. If the table promises a capability
the adapter doesn't implement, that's a doc bug; fix the doc, not the
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
availability, then calls the provider through the same request models.
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

## Availability history

History is a side effect of the watch poller, not a separate ETL. Every
successful poll appends rows to `availability_snapshots`, keyed by
`(poi_id, target_date, observed_at)`. Two principles:

- **History only exists for slots we polled.** No background backfill,
  no synthetic data. If a slot was never alerted on, there's no history
  for it. Capability-gate any history endpoint behind
  `supportsAlerts`.
- **Widen data per upstream call.** Upstreams return a window of
  per-day availability in one response. Snapshot the whole window, not
  just the alerted slot. Same upstream cost; vastly more history.

History is read through provider-agnostic SQL on the snapshot table.
Adapters do not own history queries. The snapshot lingua franca is one
reservable/date/status observation with the shared status enum:
`first_come | reserved | available | closed | unknown`. `first_come` is
visible to users but does not count as online availability; missing provider
data is `unknown`, not `closed`. Provider-specific richness (equipment-type
breakdowns, upstream map structure) is *not* captured in snapshots; that
fidelity lives in the live availability call. Snapshots are the long-tail
summary, not a replay log.

Retention is tiered: raw rows for 90 days, daily aggregates beyond,
discard raw past 1 year. The query layer reads raw or aggregates
transparently based on the requested window.

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
  ↓                                ReservationProvider.catalogAvailability
                                   ↓
                                   watch evaluator
  ↓                                  ↓ (match)
"Set watch" click                  notify (Slack / push)
  ↓                                  ↓
POST /api/availability/watches     append availability_snapshots
                                                                   list watches, pause,
                                                                   per-watch history,
                                                                   tune notification
                                                                   channel
```

The drawer captures **intent only**. The poller is the only thing that
produces matches and snapshots. The watches UI surfaces everything the
poller has produced.

## Adding a new reservation provider

1. Add a row to `ReservationProviderId` (enum) if this is a new upstream
   platform.
2. Add a `ProviderRef.<Vendor>` variant if the wire shape isn't already
   covered.
3. Create `service/reservation/adapters/<vendor>/<Vendor>ReservationProvider.kt`
   implementing `ReservationProvider`. Capabilities default conservatively
   (`supportsAlerts = false`); flip them on as features land.
4. Ensure the terminal ETL emits the right `provider_ref` JSON and that its
   `pois.source` maps to the adapter in `ReservationProviderRegistryFactory`.
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
- [reservecalifornia.md](reservation-providers/reservecalifornia.md) —
  ReserveCalifornia / Tyler Technologies discovery notes. Not implemented.
- _recgov.md, reserveamerica.md — to be written._

When adding a new vendor, follow the
[probe-vendor-api skill](../.claude/skills/probe-vendor-api/SKILL.md)
to capture the wire shape, then write `reservation-providers/<vendor>.md`
using `aspira.md` as the template. **Do not inline vendor wire
shapes in this doc** — this doc owns the architecture contract;
per-vendor docs own the wire details.

## See also

- [backend-architecture.md](backend-architecture.md) — overall layer
  rules. Adapters live under `service/reservation/`; routes consume
  availability services, not provider adapters or the provider registry.
- `rfcs/0007-availability-search-and-alerts.md` — the RFC that introduced
  this abstraction and the monitoring lifecycle it enables.
- [.claude/skills/probe-vendor-api/SKILL.md](../.claude/skills/probe-vendor-api/SKILL.md)
  — methodology for reverse-engineering a new reservation vendor's API.
