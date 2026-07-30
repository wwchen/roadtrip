# Availability providers

Campsite availability and watches are dispatched through one abstraction:
`AvailabilityProvider`. Every upstream booking or availability system (rec.gov,
Aspira NextGen, ReserveAmerica, future regional vendors) is an adapter behind
this port. Routes never call this port directly. Availability services resolve
a POI or reservable into provider-ready targets, then call the adapter.

## Why an abstraction

The dispatch logic used to live in parallel paths (single-id availability and
watch polling), each parsing legacy `provider_ref` JSON inline and importing
per-provider helper functions. Adding a third provider meant editing several
files; forgetting one was a silent bug. The current shape stores typed
`data_provider(_ref)` and `booking_provider(_ref)` columns and resolves them
through `RefResolver` plus one registry lookup behind
`AvailabilityTargetResolver`.

This doc is the contract. **A new availability provider is a typed ref plus a
provider class under `service/availability/provider/` and one row in the
registry; routes and poller core should not change.** That rule is the test of
whether the abstraction is right.

For the implementation checklist, use
[adding-a-reservation-provider.md](adding-a-reservation-provider.md). This file
explains the abstraction; the onboarding doc walks through the code, config,
tests, and operational wiring for a new provider.

## Layout

```
model/domain/provider/
├── DataProvider.kt             # catalog source identity
├── DataProviderRef.kt          # typed catalog source refs
├── BookingProvider.kt          # availability/booking provider identity
└── BookingProviderRef.kt       # typed booking refs

service/ref/
├── RefResolver.kt              # resolve one typed ref to related refs
├── DbRefResolver.kt            # DB-backed resolution matrix
└── RefValue.kt                 # typed ref wrapper values

support/
└── Dispatchable.kt              # generic `List<T>.firstHandlerFor(key)` / `allHandlersFor(key)` dispatch

service/availability/provider/
├── AvailabilityProvider.kt          # normalized availability + provider metadata port
├── RecGovAvailabilityProvider.kt
├── CampflareAvailabilityProvider.kt
├── AspiraAvailabilityProvider.kt
├── ReserveAmericaAvailabilityProvider.kt
├── ReserveCaliforniaAvailabilityProvider.kt
└── (per-vendor booking-URL / booking-display helpers beside each adapter,
    e.g. AspiraBookingUrl.kt, RecGovBookingDisplay.kt)
```

`models/availability/AvailabilityProviderCapabilities.kt` and
`models/availability/AvailabilityProviderError.kt` are shared provider-contract
types, not adapter implementation, because schedulers, API services, routes,
and provider adapters all read them.

`BookingProviderRef` (sealed interface with `RecGov` / `Campflare` / `Aspira` /
`ReserveAmerica` / `ReserveCalifornia` variants) is the availability/booking
identity. `DataProviderRef` is separate and identifies the catalog row's
source of truth.

Every vendor adapter class implements `AvailabilityProvider`, which extends
the generic `Dispatchable<BookingProvider>` from `support/Dispatchable.kt`:
the shared normalized availability contract plus identity, capabilities, ref
handling, and booking-link metadata. There is no separate registry class —
dispatch is `List<AvailabilityProvider>.firstHandlerFor(bookingProvider)` (or
`allHandlersFor` where every enabled match matters), and
`AvailabilityProvider.canHandle(key)` defaults to `isEnabled() && key == id`.
Boot wiring assembles that list as one Koin singleton
(`single<List<AvailabilityProvider>>(named("availabilityProviders"))` in
`ServiceModule.kt`), injecting each vendor's HTTP client individually. Raw
HTTP clients under `clients/` stay vendor-specific because their upstream
request and response shapes are genuinely different; each client interface is
its own `AutoCloseable` (e.g. `RecGovAvailabilityClient`,
`CampflareAvailabilityClient`). The adapter boundary is where those shapes
become provider-neutral `AvailabilityObservationBatch` values.

There is no runtime fallback-mode registry either. Availability services
enumerate candidate providers from the catalog row via
`AvailabilityTargetResolver`, and `FailoverAvailabilityFetcher` walks that
ordered candidate list on retryable failure. This is how a Campflare catalog
row can naturally fall through to a linked rec.gov alias without a
Campflare-specific service branch.

The availability orchestration that consumes this port lives one layer above:

```
service/availability/
├── DbAvailabilityTargetResolver.kt      # campsite → ordered provider candidates + date context
├── AvailabilityDateResolver.kt          # target-local earliest date/window policy
├── ResolvedAvailabilityTarget.kt        # (campsite, provider, campground, dateContext, candidates)
├── FailoverAvailabilityFetcher.kt       # walks candidates on retryable failure; records per-attempt outcomes
├── ProviderCooldownTracker.kt           # in-process demote-on-failure for BookingProvider
├── CatalogAvailabilityBatcher.kt        # groups resolved targets by (provider, parentRef, dateContext)
├── TriggerActionHandler.kt              # fire-side registry (notification/ATC kinds; unknown kinds inert)
├── NotifyTriggerActionHandler.kt        # `slack_notify` / `email_notify` → notification targets
└── alert/
    ├── AlertProvider.kt                 # who detects openings for a watch
    ├── AlertProviderRegistry.kt         # per-watch dispatch (v1: always InternalPollerAlertProvider)
    └── InternalPollerAlertProvider.kt   # today's poller-membership sync + orphan deactivation
```

`CampsiteAvailabilityService.kt` and `CampsiteCatalogService.kt` (POI →
linked campsites) live beside the resolver in `service/availability/` and are
what `CampsiteRoutes` actually calls — there is no separate top-level
`AvailabilityService.kt` file.

Availability services load persisted `Campsite` rows and resolve them into
`ResolvedAvailabilityTarget` before calling provider adapters. The
provider-ready identity lives on `BookingProviderRef` plus the campsite's own
vendor ref fields; the `Campsite` row only carries normalized catalog
metadata such as name, loop, kind, and reservation URL. When code needs table
fields, use `CampsiteRepo.findById` / query methods; when code needs to call
a provider, use `DbAvailabilityTargetResolver`.

## Provider-ref resolution

Each ETL writes its own per-vendor campground row. Availability lookups use
the campground row linked to the POI and enumerate the provider refs attached
to that row. Cross-vendor catalog matching is intentionally not part of the
product model.

**Candidate ordering** (`DbAvailabilityTargetResolver` + `RefResolver`):

1. Booking refs resolved from the campground row linked to the POI.
2. Providers that are registered and currently support the typed ref.
3. Deterministic ordering from the resolver's DB query and the provider
   registry.

`ResolvedAvailabilityTarget.candidates: List<AvailabilityProvider>` carries
the full ordered list (defaulting to just the primary `provider` when there's
only one). The batcher's private `GroupKey` still keys on the first
(preferred) candidate, so grouping semantics are unchanged; failover happens
**inside** the group fetch.

**Failover walk** (`FailoverAvailabilityFetcher`):

- Candidates are cooldown-sorted (cooling providers demoted, sole cooling
  candidate still tried).
- Retryable outcomes — `RATE_LIMITED`, `UPSTREAM_5XX`, `BLOCKED` — record a
  cooldown against the failing `BookingProvider` and continue to the
  next candidate.
- `OTHER` stops immediately (likely a bug in this env, not an outage).
- On any candidate returning OK, the fetcher clears that provider's
  cooldown and returns.

**Campsite ref translation:** when failover tries a later candidate, campsite
refs come from each resolved row's own candidate list. Observations are
recorded against the same catalog campsite ids requested by the caller.

**Per-attempt fetch-call rows:** `AvailabilityPollExecutor` writes one row
per attempt to `availability_fetch_call` (each row carries its own
`provider`, `parent_ref`, `outcome`, `duration_ms`, `error`), so failover
walks show up in the Grafana call-trace panels without extra plumbing.

**Cooldown duration:** `roadtrip.availability.provider-cooldown` (default
`5m`). In-process only; expires lazily on the next `isCooling` check.

**Preference wiring:** `GET /api/pois/{id}` reads its `availability_provider`
field straight from the campground row's own `booking_provider` column
(`CampgroundService`) — there is no separate candidate-ordering lookup for
this field.

## Alert seam

Who detects openings for a watch is a separate concern from who serves
availability. `AlertProvider` is the port; today's only implementation
`InternalPollerAlertProvider` wraps the existing poller-membership sync
(watch → poller links) and orphan-poller deactivation. A hosted-alert
implementation (e.g. Campflare's alert API) would:

- `onWatchActivated`: subscribe upstream to per-site opening notifications.
- Own a webhook route that receives vendor pushes, normalizes payloads to
  the same `CellTransition` the internal poller produces, and hands them
  to `WatchAlertDispatcher`.
- `onWatchDeactivated`: unsubscribe upstream.

**Adding a new alert provider is one file** under
`service/availability/alert/providers/<vendor>/` plus one registry entry.
`AlertProviderRegistry.forWatch` v1 always returns the internal poller;
when the alert provider becomes per-watch (based on the watch's target
vendors and adapter capability), that dispatch rule lives on the registry —
no other code changes.

`AvailabilityProviderCapabilities.supportsInternalPolling` (renamed from
`supportsAlerts` in Part 3) is the poller-side capability — "can the
internal poller poll this vendor for openings?". Hosted-alert capability
lives on the alert provider itself (`hostsAlerts: Boolean`).

## Trigger registry

`TriggerActionHandler` fires one side effect handler for a watch that just
detected an opening. A handler may cover multiple kind slugs: for example,
`NotifyTriggerActionHandler` handles both `slack_notify` and `email_notify` by
turning the watch config into a list of notification targets and sending one
aggregate alert. `TriggerActionRegistry.forKinds(kinds)` drops unknown slugs —
inert by design, matching today's `atc` behavior.

Registering a new kind is one file under `service/availability/` plus one
entry in the registry list. The `stopWhenTriggered` DONE transition still
gates on `fire()` returning true, so a handler that fails to deliver
leaves the watch active for the next poll.

## Booking provider seam

Availability providers answer "can we observe openings?" Booking adapters
answer "can Roadtrip act on this concrete opening?" The first booking action
is `ADD_TO_CART`, exposed to users as the `atc` trigger.

```
availability signal
  -> concrete campsite/date opening
  -> AvailabilityBookingTargetResolver.targetFor(action, resolved target)
  -> BookingAdapterRegistry.targetFor(parent ref + campsite ref)
  -> BookingTarget
  -> BookingAdapter.can(ADD_TO_CART, target)
  -> BookingAdapter.addToCart(request)
```

(`service/booking/BookingAdapter.kt`, `BookingAdapterRegistry.kt`,
`RecGovBookingAdapter.kt`. Unlike the availability side, booking dispatch is
a real small registry class — `BookingAdapterRegistry` keyed by adapter
`id` — not a bare `List<BookingAdapter>`; `BookingAdapter` still implements
the same `Dispatchable<BookingProvider>` interface as `AvailabilityProvider`
for its `canHandle` check.)

The adapter object is the capability source of truth: dispatch only finds
candidate adapters; each `BookingAdapter` translates the provider-specific
catalog identity it understands, and `BookingAdapter.can(action, target)`
decides target-level support. This keeps add-to-cart support out of
`AvailabilityProviderCapabilities`, because availability source and booking
system can differ. For example, Campflare may provide availability for inventory
whose booking action still happens on rec.gov, Aspira, ReserveAmerica, or
another vendor site.

Booking targets compose two identities:

- Parent booking context from the campground/facility provider ref.
- Concrete campsite/site/unit ref, which is the item added to cart.

Provider implementations own fulfillment. Rec.gov currently fulfills
`ADD_TO_CART` by calling the companion HTTP executor; a future Aspira
implementation may call a backend HTTP API instead. Companion configuration is
runtime readiness for companion-backed providers, not durable booking
capability.

Watch create/update validates `atc` trigger support against the booking-adapter
registry after resolving the watch scope to concrete campsites. Unsupported
targets fail the mutation instead of creating an active watch that can never
fulfill its trigger.

The campsite availability API exposes proposed-watch capabilities for the
current POI scope as provider-neutral `watch_capabilities`:
`trigger_kinds` includes configured notification triggers such as
`slack_notify` and `email_notify`, and includes `atc` only when the resolved
watch scope supports `BookingAction.ADD_TO_CART`. Email notification watches
carry their recipient on the watch itself as `trigger_config.email_notify.to`;
there is no environment-level recipient fallback. The FE renders the Email and
Add to cart toggles from this contract; create/update validation still uses the
same capability service and trigger-config validator as the authoritative gate.

## Capabilities

Every `AvailabilityProvider` serves availability. Capability flags only describe
optional monitoring behavior and provider limits.

```kotlin
data class AvailabilityProviderCapabilities(
    /** Can the internal poller poll this vendor for openings? */
    val supportsInternalPolling: Boolean,
    /** Max days into the future the upstream exposes. */
    val bookingHorizonDays: Int,
    /** Widest per-tick poll window. */
    val maxPollWindowDays: Int,
)
```

The API can surface this struct for the campground behind a POI so the
drawer can hide affordances the provider doesn't support.

## Supported monitoring actions

| Action | Required interface | Notes |
|---|---|---|
| Per-day availability for a window | `AvailabilityProvider.availability(ref, startDate, endDate)` | Drives provider-level availability. Adapters fetch upstream directly; the decision to serve stored data or call the adapter live is handled above it by `AvailabilityLoader`, reading current state from the `availability` interval table. |
| Catalog availability for linked campsites | `AvailabilityProvider.catalogAvailability(ref, campsites, startDate, endDate)` | The POI/campsite catalog path uses this so returned availability is narrowed to known catalog rows. |
| Capability probe | `AvailabilityProvider.capabilities` | Static per adapter; cheap. |
| Watch evaluation on poll | watch evaluator | `same_site` requires one site bookable across all N nights; `any_combination` succeeds if at least one site is open per night. |
| Record availability history | poller writes status-run rows to the `availability` interval table | Provider-agnostic; uses `AvailabilityObservationBatch` observations. |
| Notify on match | poller dispatches via configured notification triggers (`slack_notify`, `email_notify`; push future) | Channels and email recipients are not provider-specific. See `docs/superpowers/specs/2026-07-03-availability-alerts-design.md`. |

Availability providers do not model cart automation, payment, or booking on
the user's behalf. Watch flows produce matches, notifications, and
availability history only.

## Today's adapter matrix

| Provider | Availability | Watches (`supportsInternalPolling`) | Notes |
|---|---|---|---|
| RecGov (rec.gov) | ✓ | ✓ | Availability and generic watch polling. |
| Campflare | ✓ | ✓ | Availability uses v2 bulk campground availability for Campflare-owned US catalog rows. `CampflareAvailabilityProvider` sets `supportsInternalPolling = true`; the internal poller can watch it today. A hosted-alert `AlertProvider` (see "Alert seam" above) is a separate, not-yet-built seam for vendor-pushed openings instead of internal polling. |
| Aspira NextGen (BC Parks, Washington, Pennsylvania) | ✓ | ✓ | `AspiraAvailabilityProvider` sets `supportsInternalPolling = true`. |
| ReserveAmerica / Active Network (Alberta Parks, New York State Parks) | ✓ | ✗ | Availability reads the live campsite-calendar matrix; sites are cataloged from that same calendar roster (see `reserveamerica.md`). `supportsInternalPolling = false` until upstream cadence/load limits are validated. |
| ReserveCalifornia / Tyler | ✓ | ✗ | Availability reads standard facility grids. Catalog import uses the public Search All Parks `search/place` flow. `supportsInternalPolling = false`. |

Source of truth for the Watches column is each adapter's
`capabilities.supportsInternalPolling` (`AvailabilityProviderCapabilities`),
not this table — if they disagree, fix the table.

When a row is added here, it should match a real file in
`service/availability/provider/`. If the table promises a
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

A run can cover many campsites (a POI-scope watch fans out to every child
campsite), but the poller does not issue one upstream call per campsite.
`CatalogAvailabilityBatcher` groups a run's resolved targets by
`(provider, parentRef, dateContext)` and issues exactly one
`catalogAvailability` call per group — so N campsites under one campground
become one upstream call. This is what fixed the old per-site rate-limit
fan-out. Call-shaping stays inside each adapter (months for rec.gov, the park
matrix for ReserveAmerica, per-day map calls for Aspira); the batcher and poller
never branch on vendor, they only group and dispatch.

Before a grouped poll calls the adapter, the executor checks the
`availability` interval table for fresh full-window coverage. If every
`(campsite_id, target_date)` cell in that group's vendor polling window has a
current observation newer than the poller's effective cadence, the group is
skipped before the vendor governor and no `availability_run` /
`availability_fetch_call` rows are written. Missing cells or cells older than
the cadence window make the group fetch normally.

`availability_fetch_call` is the trace table for this grouping: one row per
group call, keyed by `run_id`, with columns `provider`, `parent_ref`,
`campsite_count`, `window_start` / `window_end`, `outcome`
(`ok | rate_limited | upstream_5xx | blocked | other`), `duration_ms`, and
`error`. Rows are written only when a real upstream call was made — a group
with no future dates is skipped by the batcher and produces no row. This
table is surfaced in the "Availability Watch drill down" Grafana
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
  `supportsInternalPolling`.
- **Widen data per upstream call.** Upstreams return a window of
  per-day availability in one response. Record the whole window, not
  just the alerted slot. Same upstream cost; vastly more history.

History is read through provider-agnostic SQL on the `availability` table.
Adapters do not own history queries. The lingua franca is one
campsite/date/status observation with the shared status enum:
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
"Set watch" click                  notify (Slack / email / push)
  ↓                                  ↓
POST /api/watches                  record availability status-runs
                                                                   list watches, pause,
                                                                   per-watch history,
                                                                   tune notification
                                                                   channel
```

The drawer captures **intent only**. The poller is the only thing that
produces matches and snapshots. The watches UI surfaces everything the
poller has produced.

## Adding a new availability provider

1. Add rows to `DataProvider` and/or `BookingProvider` if this is a new
   catalog source or availability platform.
2. Add `DataProviderRef` and `BookingProviderRef` variants if the identifier
   shapes are not already covered.
3. Create `service/availability/provider/<Vendor>AvailabilityProvider.kt`
   implementing `AvailabilityProvider`. Capabilities default conservatively
   (`supportsInternalPolling = false`); flip them on as features land.
4. Ensure the terminal ETL emits the right `dataProviderRef` and optional
   `bookingProviderRef`, and that registry wiring maps configured sources to
   the adapter.
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
  rules. Providers live under `service/availability/provider/`; routes consume
  availability services, not provider adapters or the provider registry.
- `rfcs/0007-availability-search-and-alerts.md` — the RFC that introduced
  this abstraction and the monitoring lifecycle it enables.
- [.claude/skills/probe-vendor-api/SKILL.md](../.claude/skills/probe-vendor-api/SKILL.md)
  — methodology for reverse-engineering a new reservation vendor's API.
