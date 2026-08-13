---
title: Planning Mode — template shelf and source-of-truth timeline
authors:
  - Will (drafted with Claude)
created: 2026-08-12
last_updated: 2026-08-12
rfc_pr: TBD
status: Draft
---

# Proposal: Planning Mode — template shelf and source-of-truth timeline

## Summary

Add a planning surface with two parts. The **shelf** is a set of hand-authored
roadtrip **templates** (e.g. "Vegas → Moab Mighty 5, 8 days") presented as
compare cards: days, parks included, driving load, EV ease, booking ease,
season window, estimated budget. Picking a template and a start date
instantiates a **trip**: a shared, dated timeline that maintains four facts —
what day each stop is, where the car charges, which campgrounds must be booked
(with live per-night state wired to the existing watch/poller/ATC machinery),
and how much driving each day holds. Templates carry precomputed route and
charging data authored once, so v1 needs **no routing engine and no runtime
energy solver**; the only live computations are dates, availability, and
booking state.

## Motivation

Trip planning today is 10–15 tabs whose facts silently go stale (availability,
permit windows, closures, charger status). 71% of travelers find planning
stressful and 67% report information-overload paralysis; 67% have argued with
a travel companion over it. Roadtrip already owns the hardest live facts —
campsite availability, watches, and real rec.gov add-to-cart — but exposes
them per-campground, not per-trip. Meanwhile the EV-camping questions ("is
this route easy for my car?", "which night needs a hookup?") are answered
nowhere on the internet in one place.

Planning Mode aims the existing plumbing at a whole trip and adds the one
thing competitors structurally lack: a plan that re-verifies itself and tells
you when a fact you agreed on has changed.

The audience is two people with asymmetric knowledge: the Tesla owner (thinks
in %, kW, hookup nights) and a partner who has never owned an EV and wants to
decide where to eat and what to see. The same timeline must read at both
altitudes.

## Goals

- Compare 3+ authored trips on one screen and pick one in minutes, not weeks.
- One tap from the timeline: cart what's open, watch what's full, across every
  night of the trip (rec.gov ATC where supported; notify elsewhere).
- Every claim on the timeline shows its booking state and last-verified time.
- EV facts (charger stops, hookup-critical nights, driving load) are visible
  as ambient status ("battery weather") without requiring EV literacy.
- A frozen trip survives zero connectivity: screenshot-ready trip card, later
  an offline cache.
- Success metric (Direction A): we use it for a real trip and close the other
  tabs.

## Non-Goals

- **No turn-by-turn navigation.** The timeline ends in a hand-off to
  Google/Apple/Tesla nav. If it changes while you're driving, it's not our job.
- **No runtime routing engine or energy solver** in v1. Route geometry,
  distances, and charging plans are authored into templates.
- **No chat UI.** Fuzzy questions stay in chat surfaces; a later MCP surface
  can read/propose against trips.
- **No generalized trip editor from scratch** (blank-canvas planning), no
  voting/consensus mechanics, no food-POI database, no gear/marketplace/
  community features, no subscriptions.
- No iMessage/satellite notification channel in v1 (parked; screenshot + Slack
  /email cover it).

## Proposal

### Concepts

- **Template** — an authored, versioned trip shape: ordered days, each with a
  drive leg (miles, minutes, chargers en route, hookup-critical flag), a stay
  (reference to a cataloged campground where possible), highlights, and
  authored metadata: season window, permit/lead-time notes, EV-ease and
  booking-ease grades, budget constants. Templates are content, not user data:
  YAML files in the repo (same spirit as `poi-registry.yaml`), loaded at boot.
- **Trip** — a template instantiated with a start date (and later: party,
  vehicle profile). Rows in Postgres. A trip owns its nights and their booking
  states; edits (skip a day, swap a campground) apply to the trip, never the
  template.
- **Trip night** — one date at one campground. Carries a booking state and
  optional links to a watch and/or a held/booked campsite.
- **Booking state machine** (per night):
  `unplanned → bookable → in_cart (ATC) → booked`, with side states
  `watching` (full; watch active), `walk_in` (first-come site), and
  `call` (not bookable by any integrated provider; show phone from
  `campgrounds.contact`).

### Template schema (sketch)

```yaml
templates:
  - id: mighty-five-classic
    name: "Mighty 5 Classic"
    origin: "Las Vegas, NV"
    days: 8
    season:
      prime: [apr, may, sep, oct]
      notes: "UT-12 summit 9,600 ft — early/late season snow possible"
    ev:
      grade: yellow          # green | yellow | red
      max_supercharger_gap_mi: 190
      hookup_critical_nights: [3]     # day index; hookup covers the gap
      notes: "Bryce→Torrey stretch has no Supercharger; 50A night before"
    booking:
      grade: red
      lead_time_days: 150
      scarce: [devils-garden, watchman]
    budget:
      camp_nights: 7
      est_charging_kwh: 300
    itinerary:
      - day: 1
        drive: { from: "Las Vegas", to: "Springdale", miles: 160, minutes: 170,
                 superchargers: [st-george-ut, hurricane-ut] }
        stay:  { campground: { provider: recgov, ref: "232445" } }  # Watchman
        highlights: ["Canyon Overlook sunset"]
        sidequests: ["Valley of Fire SP (+40 min)"]
      # ... days 2–8
```

Campground references resolve against the canonical catalog by
`(provider, ref)` via the existing vendor-ref tables; a template may also
declare an uncataloged stay (`manual: {name, phone, url}`) which renders as a
`call` night.

### Shelf and compare cards

`GET /api/templates` returns the authored set with card fields. Cards render:
days · parks/highlights · total miles, avg h/day, longest day · EV ease with
one-line reason · booking ease with lead time · season window · estimated
budget. All card data is authored or derived from authored numbers plus
catalog prices — nothing on the shelf requires live calls.

### Trip timeline (the source of truth)

`POST /api/trips {template_id, start_date}` materializes dated nights.
The timeline view maintains the four facts:

1. **What day** — every stop dated; changing `start_date` re-dates everything
   and re-evaluates season fit and booking lead-time warnings.
2. **Where you charge** — supercharger stops per leg (joined live to
   `tesla_superchargers` for `site_status`, `stall_count`, `max_power_kw`,
   pricing from `pricebooks`), plus hookup-critical night markers.
3. **What you must book** — nights sorted scarce-first with live state.
   "Check all nights" fans out through existing availability slots; "watch
   what's full" creates watches (existing `/api/watches` semantics) with
   `atc` trigger where the provider supports it.
4. **How much driving** — authored per-day miles/minutes, longest-day flag.

Freshness: every live fact renders its `checked_at` from the existing
availability observations; stale facts (older than the poller cadence) show
as stale rather than pretending.

### Two-audience rendering

Same timeline, two densities:

- **Driver view**: charger names, gaps, hookup flags, per-day battery weather
  glyph (green/yellow/red, authored in v1).
- **Partner view** (default): dates, places, photos, highlights, food/sidequest
  slots — the decisions — with EV facts reduced to the glyph and a plug icon.
  Nothing in this view asks for EV knowledge; nothing in it is less true.

### Backend shape

Per layering rules: `PlanningRoutes` (HTTP shell) → `PlanningService`
(instantiation, date math, booking-state resolution, fan-out to existing
`CampsiteAvailabilityController`/watch service) → `TripRepo` (new tables
`trips`, `trip_nights`). Templates load through a `TripTemplateRegistry`
(YAML, validated at boot: every campground ref must resolve, every
supercharger slug must exist). No new route→repo paths; no SQL outside repo.

## Data audit — what we have vs what v1 needs

| Fact the timeline conveys | Status | Where it lives / what's missing |
|---|---|---|
| Campground identity, location, photos, descriptions | ✅ have | `campgrounds` (+ vendor refs) |
| Live per-night availability, watches, alerts, rec.gov cart | ✅ have | `availability`, watch/poller/governor, companion ATC |
| Per-carrier cell coverage at camp | ✅ have | `campgrounds.cell_service` JSONB |
| Camp price for budget | ✅ have (quality varies) | `campgrounds.price`, `campsites.price` JSONB |
| Supercharger locations, status, stalls, power, $/kWh | ✅ have | `tesla_superchargers` incl. `pricebooks` |
| Campground closures/warnings | 🟡 partial | `campgrounds.alerts` JSONB; no park-level NPS alert feed |
| Electric hookup **presence** | ✅ have | `campsites.electric_hookups` BOOLEAN |
| Electric hookup **amperage** (30A vs 50A ≈ 3× charge rate) | ❌ missing | Likely recoverable from `campsites.source_payload` (rec.gov attributes) — ETL extraction + column, no new fetching. **Verify early.** |
| Destination / L2 chargers (Springdale, Torrey hotels) | 🟡 verify | tesla.com get-locations feed may include destination chargers — check `tesla-locations` payloads |
| Drive distances, times, elevation | ❌ runtime | Authored into templates (v1 answer); routing engine is the someday answer |
| Permit/lottery/timed-entry calendars (Angels Landing, Arches) | ❌ missing | Authored template notes in v1; structured deadline data is M-later |
| Seasonal temps / weather | ❌ missing | Authored season windows in v1 |
| Food & sidequest POIs | ❌ missing | Authored template content; not a database |
| Private campgrounds (e.g. Ruby's Inn) bookability | ❌ missing | `call` state + phone from `campgrounds.contact` or manual stay |
| Park entry fees / pass math | ❌ missing | Budget constants in template/config |

The audit's headline: **the live, hard-to-get facts are already in the
database; almost everything missing is authorable content.** That is what
makes the template approach cheap.

## Rationale

- **Templates vs. solver/blank canvas**: a generative planner needs routing,
  energy modeling, and POI ranking before it produces anything trustworthy;
  authored templates deliver a usable, correct product with zero new
  infrastructure, and curation quality is itself the differentiator. The
  solver can arrive later as feedback on top of the same trip object.
- **YAML content vs. DB-managed templates**: repo YAML matches the existing
  registry pattern, versions with code review, and avoids building an
  authoring UI for an audience of two.
- **Per-trip fan-out vs. new availability machinery**: reusing slots/watches
  keeps one rate-limit governor and one truth path; Planning Mode is a new
  *caller*, not a new *system*.

## Unresolved questions

- Is amperage actually present in stored rec.gov `source_payload` envelopes,
  and for what fraction of campsites? (Decides whether "hookup-critical
  night" can be computed rather than authored.)
- Trip sharing/auth: share-token read-only link vs. rides on the existing
  auth provider layer (RFC 0009)?
- Does the `tesla-locations` feed include destination chargers, or only
  Superchargers?
- Budget constants (entry fees, pass price, default $/kWh when pricebook is
  empty): template-level vs. global config?
- Milestone slicing (proposed): **M1** shelf + 3 templates + instantiate +
  read-only timeline with live booking states → **M2** cart/watch-all +
  scarce-first list → **M3** trip card (screenshot/PDF) + offline cache →
  **M4** amperage extraction + computed battery weather → **M5** time-aware
  "today card".

## Decision log

| # | Date | Decision | Rationale |
|---|---|---|---|
| 1 | 2026-08-12 | Templates over solver for v1 | No routing/energy infra needed; curation is the moat |
| 2 | 2026-08-12 | Voting/consensus mechanics cut | Source of truth, not a game; different problem |
| 3 | 2026-08-12 | iMessage/satellite channel parked | Screenshot + existing notify channels cover v1 |
