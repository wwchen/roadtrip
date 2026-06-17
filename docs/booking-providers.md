# Booking providers

Campsite availability and watches are dispatched through one abstraction:
`BookingProvider`. Every upstream reservation system (rec.gov, Aspira NextGen,
Camis, future regional vendors) is an adapter behind this port. Routes,
the watch poller, and any future endpoint never branch on a sealed
`ProviderRef` — they consume the interface.

## Why an abstraction

The dispatch logic used to live in three parallel `when` blocks (single-id
availability, bulk availability, watch poller), each parsing
`provider_ref` JSON inline and importing per-provider helper functions.
Adding a third provider meant editing three files plus a fourth parser;
forgetting one was a silent bug. The port collapses that into one
registry lookup.

This doc is the contract. **A new booking provider is a new file under
`service/booking/adapters/<vendor>/` and one row in the registry — nothing
else outside that directory should change.** That rule is the test of
whether the abstraction is right.

## Layout

```
service/booking/
├── BookingProvider.kt          # availability port (mandatory)
├── BookingProviderId.kt        # enum/provider identity
├── BookingProviderRegistry.kt  # forPoi(row) → adapter
├── ProviderRefParser.kt        # JSONB → models.ProviderRef (single source)
├── WatchEvaluator.kt           # match logic per provider (mandatory)
├── AutoBooker.kt               # cart/book port (optional capability)
└── adapters/
    ├── recgov/                 # availability
    ├── aspira/                 # availability
    └── camis/                  # availability stub
```

`models.ProviderRef` (sealed class with `RecGov` / `Aspira` / `Camis`
variants) is the wire shape. Adapters take a `ProviderRef` of their
matching variant and the registry guarantees the dispatch is correct.

## Capabilities

Not every provider supports every monitoring action. The capability flags
on each provider drive what the FE shows.

```kotlin
data class BookingCapabilities(
    /** Can we serve per-day availability for a window? */
    val supportsAvailability: Boolean,
    /** Can we poll for openings and notify on match? */
    val supportsAlerts: Boolean,
    /** Can we add to cart / book on the user's behalf? */
    val supportsAutoBook: Boolean,
    /** Max days into the future the upstream exposes. */
    val bookingHorizonDays: Int,
)
```

The API can surface this struct for the campground behind a POI so the
drawer can hide affordances the provider doesn't support.

## Supported monitoring actions

| Action | Required interface | Notes |
|---|---|---|
| Per-day availability for a window | `BookingProvider.availability(ref, start, days, force)` | Drives the drawer's week grid. Per-month cache lives in the adapter. |
| Capability probe | `BookingProvider.capabilities` | Static per adapter; cheap. |
| Watch evaluation on poll | watch evaluator | `same_site` requires one site bookable across all N nights; `any_combination` succeeds if at least one site is open per night. |
| Append history snapshot | poller writes `availability_snapshots` row | Provider-agnostic; uses the standard `AvailabilityResult` shape. |
| Notify on match | poller dispatches via Slack / push (future) | Channels are not provider-specific. |
| Auto-add-to-cart | `AutoBooker.addToCart(ref, match, session)` | Optional. Provider implements only if it can. Capability flag gates the FE toggle. |
| Auto-book / pay | not modeled | Out of scope. |

A provider that only implements `BookingProvider` (not `AutoBooker`) is
fully functional for browse + watch flows. Auto-book is the genuinely
optional capability and is modeled as a separate interface so adapters
that don't support it don't have to throw `UnsupportedOperationException`
stubs.

## Today's adapter matrix

| Provider | Availability | Watches | Auto-book | Notes |
|---|---|---|---|---|
| RecGov (rec.gov) | ✓ | ✓ | ✗ | Availability and generic watch polling. Auto-book was part of the removed legacy `/campsite` app. |
| Aspira NextGen (BC Parks, Washington, Pennsylvania) | ✓ | planned | ✗ | Availability ships now; watch dispatch still needs work. Auto-book requires session handling we haven't built. |
| Camis (Alberta Parks) | stub | ✗ | ✗ | Adapter file exists so the registry returns a typed null; throws `Unsupported` on call. POIs render without the week grid until the real adapter lands. |

When a row is added here, it should match a real file in
`service/booking/adapters/<vendor>/`. If the table promises a capability
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

Adapters do not own polling cadence — the platform poller does. Adapters
expose a single `availability(ref, start, days, force)` call. Cadence,
backoff, dedup, and the "should we poll right now" decision all live
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
Adapters do not own history queries — the snapshot shape
(`available_count`, `total`, `status`) is the lingua franca. The shared
status enum is `first_come | reserved | available | closed | unknown`.
`available_count` counts only online-bookable `available` reservables.
`first_come` is visible to users but does not count as online availability;
missing provider data is `unknown`, not `closed`. Provider-specific
richness (per-site detail, equipment-type breakdowns) is *not* captured in
snapshots; that fidelity lives in the live availability call. Snapshots are
the long-tail summary, not a replay log.

Retention is tiered: raw rows for 90 days, daily aggregates beyond,
discard raw past 1 year. The query layer reads raw or aggregates
transparently based on the requested window.

## Lifecycle: how a user's intent becomes a booking

```
Drawer (this product)              Poller (background)             Watches UI
─────────────────────             ──────────────────               ──────────────────
browse → pin click
  ↓
GET /api/pois/{id}
  ↓
GET /api/poi/{poi_id}/availability (per active watch, every cycle)
  ↓                                BookingProvider.availability
week pages                           ↓
  ↓                                watch evaluator
  ↓                                  ↓ (match)
"Set watch" click                  notify (Slack / push)
  ↓                                  ↓ (if capability)
POST /api/availability/watches     AutoBooker.addToCart
                                     ↓
                                   append availability_snapshots
                                                                   list watches, pause,
                                                                   per-watch history,
                                                                   tune notification
                                                                   channel, toggle
                                                                   auto-cart
```

The drawer captures **intent only**. The poller is the only thing that
produces matches, snapshots, and bookings. The watches UI surfaces
everything the poller has produced.

## Adding a new booking provider

1. Add a row to `BookingProviderId` (enum) and to `booking_provider` table
   (Flyway migration if the FK target needs it).
2. Add a `ProviderRef.<Vendor>` variant if the wire shape isn't already
   covered.
3. Create `service/booking/adapters/<vendor>/<Vendor>BookingProvider.kt`
   implementing `BookingProvider`. Capabilities default conservatively
   (`supportsAlerts = false`, `supportsAutoBook = false`); flip them on
   as features land.
4. (Optional) Add `<Vendor>WatchEvaluator` and `<Vendor>AutoBooker` if
   the provider supports those capabilities.
5. Wire the adapter into `BookingProviderRegistry` (one line).
6. Update the matrix table above.

Steps 1–6 should be the entire diff. If you find yourself editing route
files or the watch poller core, the abstraction is leaking — fix that
before merging.

## Per-vendor API docs

Each adapter's upstream API is documented separately under
`docs/booking-providers/`:

- [aspira.md](booking-providers/aspira.md) — Aspira NextGen
  (`reservation.pc.gc.ca`, `camping.bcparks.ca`,
  `washington.goingtocamp.com`).
- _recgov.md, camis.md — to be written._

When adding a new vendor, follow the
[probe-vendor-api skill](../.claude/skills/probe-vendor-api/SKILL.md)
to capture the wire shape, then write `booking-providers/<vendor>.md`
using `aspira.md` as the template. **Do not inline vendor wire
shapes in this doc** — this doc owns the architecture contract;
per-vendor docs own the wire details.

## See also

- [backend-architecture.md](backend-architecture.md) — overall layer
  rules. Adapters live under `service/`; routes consume the registry,
  not the adapters directly.
- `rfcs/0007-availability-search-and-alerts.md` — the RFC that introduced
  this abstraction and the monitoring lifecycle it enables.
- [.claude/skills/probe-vendor-api/SKILL.md](../.claude/skills/probe-vendor-api/SKILL.md)
  — methodology for reverse-engineering a new booking vendor's API.
