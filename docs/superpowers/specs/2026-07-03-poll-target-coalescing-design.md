# Poller coalescing (watch → poller)

Date: 2026-07-03
Status: proposed
Related: `docs/reservation-providers.md`, `rfcs/0007-availability-search-and-alerts.md`, `docs/superpowers/specs/2026-07-02-availability-fetch-batching-and-trace-design.md`

> Naming: the physical unit was "poll target" in early drafts → renamed **poller**
> (one word, clean DB name, no clash with `AvailabilityTargetResolver` /
> `ResolvedAvailabilityTarget`). Filename kept to avoid churn.

## Problem

On the `reservable-watch-drill` dashboard, two watches on the **same
campground** (POI 2006, Upper Pines) run as two independent jobs and issue two
independent rec.gov fetches per cycle:

| | scope | dates |
|---|---|---|
| watch 1 / job 1 | whole POI (235 reservables) | 2026-07-06 → 07-08 |
| watch 2 / job 2 | one reservable (`site:recgov:100`) | 2026-07-01 → 07-03 |

rec.gov availability is **per-campground-per-month**: one call returns the whole
campground's month regardless of which sites or dates the caller wants. Both
jobs fetch Upper Pines July availability — two near-identical upstream calls.
Today's dedup (`CatalogAvailabilityBatcher`, 07-02 spec) collapses fan-out
*within one run*; it never crosses job boundaries. `reservation-providers.md`
*promises* the cross-watch version ("Free dedup across users… share one poll"),
but the unit of work is the watch/job (1:1), not the shared upstream call — so
the promise is undelivered.

## Why this matters (product)

The product is: **monitor campgrounds, catch last-minute cancellations, alert the
user fast enough to grab the site.** Cancellations re-snap in seconds on hot
grounds, so tight polling is the whole value — but tight polling of popular
grounds by many users trips a vendor's rate limit, and a 429 means we **miss the
cancellation we were polling for.** Coalescing is therefore not an optimization;
it is what makes tight polling survivable. Everything below (poller → run → fetch
→ snapshot) is plumbing whose only job is to make the **alert** fast, reliable,
and cheap enough to run continuously.

The fix is not "run once" — the two intents differ and must alert independently.
The fix is to **coalesce the upstream fetch across watches** while keeping
per-watch intent intact.

## Design rules

1. **Separate intent from physics.** A *watch* is user intent; a *poller* is the
   intent-free physical unit that makes upstream calls.
2. **Coalesce at set/edit time.** When a watch is written, resolve it to the
   upstream call unit(s) it touches and link it, so the schedule doesn't re-derive
   grouping every tick.
3. **Derive values, materialize scheduling flags.** A poller derives its window
   and cadence at run start from live watches (no drift). It *stores* only the
   flags the orchestrator indexes on — `next_run_at`, `active` — because the claim
   query needs a cheap index. That's the one deliberate exception to "don't store."
4. **The physical key is the vendor's call unit** (`parentRef`), not the POI —
   see below.

## The coalescing key is `parentRef`, not `poi`

`DbAvailabilityTargetResolver.resolve` maps a reservable via `poiIdsForReservable`
(reservable↔POI is **M:N**, RFC 0008) to a parent POI's
`parentRef = ProviderRefParser.parse(providerRefJson)`. `CatalogAvailabilityBatcher`
groups by `(provider, parentRef, dateContext)` and `availability_fetch_call` is
keyed by `(provider, parent_ref)`. **The whole stack coalesces on `parentRef`.**

If a poller keyed on `poi`, two POIs backed by one vendor call unit (an Aspira
*map* can back several POIs; a reservable can sit under several POIs) would create
two pollers making the **same** upstream call — the duplicate-fetch bug, resurrected
for non-1:1 vendors. So the poller keys on **`(provider, parent_ref)`** — the exact
grain the batcher and fetch trace already use, making **one poller == one upstream
call unit** by construction. It carries a *representative* `poi_id` for coordinates
(date-context) and fans out to *all* reservables resolving to that `parentRef`.
(For rec.gov this is 1:1 with a POI; keying on `parentRef` just makes us correct
when a vendor shares a call unit across POIs.)

## Nomenclature (convention-aligned, one word each)

| Layer | Name | Key | Owns |
|---|---|---|---|
| intent (N:M) | **watch** | `watch_id` | set of POIs/reservables, nights, desired cadence, trigger, filters, notification |
| physical (schedulable) | **poller** | `(provider, parent_ref)` | scheduling flags only (`next_run_at`, `active`) |
| execution | **run** | `run_id` | status, transition count, duration |
| upstream request | **fetch** | `(run, provider, parent_ref, bucket)` | outcome, duration |
| current state | **cell** | `(reservable, target_date)` | latest status + `last_observed_at` |
| history | **snapshot** | `(reservable, target_date, observed_at)` | one status *transition* |
| *(future)* firing/event | **alert** *(reserved)* | — | — |

`alert` is reserved for the future firing event (Prometheus/Datadog convention),
not standing config (that's `watch`). "Job" retires; the generic `Scheduler<T>` /
`Schedulable` framework nouns stay — the poller is the concrete `Schedulable`.

## Model

```
POI    N ─── M  Reservable
Watch  N ─── M  Poller          ← coalescing edge (join)
Poller   = Schedulable          ← stores { provider, parent_ref, poi_id (repr), active, next_run_at, claim }
Poller 1 ─── N  Run
Run    1 ─── K  Fetch           ← one call per provider bucket over the derived window
Fetch  ──►  Cell   (upsert current state + last_observed_at, every poll)
       └─►  Snapshot (append a transition row ONLY on status change)
```

Chain: **`watch → poller → run → fetch → {cell, snapshot}`**.

### The availability cube (your mental model, realized)

A **cell** is `(reservable × target_date)`. Its history along the time axis is a
sequence of status transitions `available → reserved → available → … → past`. Two
tables split "now" from "history":

- **`availability_cell`** — the current face: one row per cell, upserted every
  poll. `status` (latest) + `last_observed_at` (liveness: "confirmed 10s ago" vs
  "stale") + `last_changed_at`.
- **`availability_snapshot`** — the depth axis: **edge-triggered**, one row *only
  when a cell's status changes*. A fully-booked cell polled every 30s for a week =
  1 row, not ~20 000. A new row with `status = available` in a watch's sub-cube
  *is* the alert trigger (edge detection for free).

Terminal `past`: when `target_date < today` the poller stops covering the cell
(window clamp) and its history simply ends.

### The seam (lossy projection)

A watch projects **down** to only *which parentRefs it touches* (links) + its
*nights* and *desired cadence* (read at run start). Trigger, `reservable_filters`,
notification, the reservable subset stay in the watch and re-apply **up** at eval
by reading the cube. The poller stores none of it.

### Over-fetch is intentional and lossless

One call over-covers every watch on that parentRef — a superset. Cells record the
finest grain (reservable × day), so any watch reconstructs its answer by filtering.
We never make a filtered/single-site upstream call.

## Schema

```sql
-- WATCH: intent header. Drop single-scope poi_id/reservable_id + scope CHECK
-- (moved to watch_target). cadence_sec → nullable desired override (NULL = fall
-- through). target_dates stays a SET OF INDEPENDENT NIGHTS; contiguity ("N nights,
-- same site") is DERIVED at eval from adjacent available cells, not stored.
ALTER TABLE availability_watch DROP CONSTRAINT availability_watch_scope_check;
ALTER TABLE availability_watch DROP COLUMN poi_id, DROP COLUMN reservable_id;
ALTER TABLE availability_watch ALTER COLUMN cadence_sec DROP NOT NULL;

CREATE TABLE availability_watch_target (
  watch_id       BIGINT NOT NULL REFERENCES availability_watch(id) ON DELETE CASCADE,
  poi_id         BIGINT REFERENCES pois(id)        ON DELETE CASCADE,
  reservable_id  BIGINT REFERENCES reservables(id) ON DELETE CASCADE,
  CHECK ((poi_id IS NOT NULL) <> (reservable_id IS NOT NULL))
);

-- POLLER: shared schedulable (absorbs availability_job). Identity = vendor call
-- unit. Stores ONLY scheduling flags; window/cadence/refcount are derived in-run.
-- `active` is the materialized flag the claim index needs; it toggles active⇄
-- dormant (NOT a terminal 'done'): a new watch revives a dormant poller.
CREATE TABLE availability_poller (
  id             BIGSERIAL PRIMARY KEY,
  provider       TEXT        NOT NULL,
  parent_ref     TEXT        NOT NULL,          -- CatalogAvailabilityBatcher parentRefKey()
  poi_id         BIGINT      NOT NULL REFERENCES pois(id) ON DELETE CASCADE,  -- representative, for coords
  active         BOOLEAN     NOT NULL DEFAULT TRUE,
  next_run_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  claimed_until  TIMESTAMPTZ,
  claim_token    TEXT,
  last_run_at    TIMESTAMPTZ,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (provider, parent_ref)
);
CREATE INDEX availability_poller_due_idx
  ON availability_poller (next_run_at) WHERE active;

CREATE TABLE availability_watch_poller (
  watch_id   BIGINT NOT NULL REFERENCES availability_watch(id)  ON DELETE CASCADE,
  poller_id  BIGINT NOT NULL REFERENCES availability_poller(id) ON DELETE CASCADE,
  PRIMARY KEY (watch_id, poller_id)
);

-- Per-POI cadence override (RFC 0007's deferred column).
ALTER TABLE pois ADD COLUMN cadence_override_sec INT
  CHECK (cadence_override_sec IS NULL OR cadence_override_sec >= 5);

-- CELL: current face of the cube, upserted every poll.
CREATE TABLE availability_cell (
  reservable_id    BIGINT      NOT NULL REFERENCES reservables(id) ON DELETE CASCADE,
  target_date      DATE        NOT NULL,
  status           TEXT        NOT NULL,          -- shared status enum
  last_observed_at TIMESTAMPTZ NOT NULL,
  last_changed_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (reservable_id, target_date)
);

-- SNAPSHOT: transition log. Same shape as today; write policy changes to
-- edge-triggered (append only when status differs from the cell's prior status).
-- availability_job_run → availability_run; job_id → poller_id.
ALTER TABLE availability_job_run RENAME TO availability_run;
ALTER TABLE availability_run RENAME COLUMN job_id TO poller_id;  -- FK re-pointed in body
```

`availability_fetch_call` is unchanged (already `(run_id, provider, parent_ref)`).

## Membership maintenance (set time)

Pure function of active watches, recomputed transactionally on every watch write;
touches *links* only:

1. Resolve the watch's target set → distinct `parentRef`s (reservables/POIs →
   provider ref via the resolver).
2. Upsert one `availability_poller` per parentRef; **revive** it (`active = true`,
   pull `next_run_at`) if it was dormant. Insert/delete
   `availability_watch_poller` links to match.
3. Optional single write: a *tighter* desired cadence pulls `next_run_at` earlier
   so the speed-up takes effect this cycle.
4. A poller with no remaining links → `active = false` (dormant; a tick would also
   reap it — this is the eager path).

## Lifecycle & derived state

Effective state is a pure function of live watches + today, computed each run:

- **Live link:** watch is `active` **and** has ≥1 `target_date ≥ earliestDate`
  (`AvailabilityDateResolver`). An all-elapsed watch stops counting though nobody
  removed it.
- **Derived tuple** (never stored): `window = (min..max of live nights) ∩
  [earliestDate, horizon]` (both edges move — front clamps forward, back retreats
  as members elapse); `cadence` = fall-through min (see below); `refcount` =
  count(live links), for queries only.
- **The tick is the reaper — no janitor.** Empty derived window for the whole
  poller → executor **retires instead of rescheduling**: elapsed watches → `done`,
  links dropped, poller `active = false`, `next_run_at` not advanced. This delivers
  the doc's "stops when the date elapses," which the current executor does *not*
  (it reschedules empty runs forever — a pre-existing bug this closes).
- **≤1 empty tick of lag**; that tick makes no upstream call (null window → skip).
- **Creating an already-elapsed watch is rejected** via `resolveWindow`'s existing
  `BadDateWindow.StartBeforeEarliest`.
- **`active` is not terminal** — `active ⇄ dormant`; a new/edited watch revives.

## Cadence & rate — three forces on `next_run_at`

```
next_run_at = force ? now
                    : now + min( resolve(w) for live w )      -- governor gates the FETCH, not this value
resolve(watch) = watch.cadence_sec ?? poi.cadence_override_sec ?? GLOBAL_DEFAULT_SEC
```

1. **Target cadence** (derived in-run): fall-through, tightest live watch wins.
   Hot ground → `poi.cadence_override_sec = 30`; sleepy ground → global 5 m.
2. **Vendor rate budget** (global per vendor, durable): a **Bucket4j token bucket
   backed by Postgres**, keyed by vendor, capacity/refill from config. The executor
   acquires *K tokens* (K = number of provider buckets it's about to fetch) **before
   fetching**; if unavailable, it **skips the fetch and reschedules soon** (no
   upstream call, no wasted 429). Postgres backing means a restart doesn't reset the
   budget and burst. Cadence is a *target, not a guarantee* — under pressure real
   intervals stretch for everyone (better than a ban). Per-poller **backoff** stays
   as the reactive net (429 despite the governor). No leak: the governor knows only
   `vendor → budget`.
3. **Force pull** — "check now" sets `next_run_at = now`, force-fetches (executor
   already passes `force = true`); still draws tokens, with a per-poller cooldown.

Chosen because single-process + must-survive-restart: no distributed coordination,
but the rate state is durable in Postgres via Bucket4j.

## Executor changes

`AvailabilityPollExecutor.handle(poller)` (was `handle(job)`):

- Load the poller's **live watches**; derive `window` + `cadence`; resolve
  reservables as *all reservables under `poller.parent_ref`*. The
  `AvailabilityJobIntent` sealed type + per-site variant are removed.
- Derive the fetch buckets; **acquire K vendor tokens**; on failure reschedule soon
  without fetching (governor backpressure).
- Fetch via `CatalogAvailabilityBatcher.fetchByGroup` (unchanged);
  `availability_fetch_call` trace + reactive backoff unchanged.
- **Cube write (new):** for each observed cell, upsert `availability_cell`
  (`last_observed_at` always; `status`/`last_changed_at` on change) and **append an
  `availability_snapshot` transition row only when status changed**. `run.snapshot_count`
  becomes transitions/run.
- **Reschedule branches:** empty window → retire; governor-starved → reschedule
  soon; success → `next_run_at = now + cadence`.

The `Scheduler` is unchanged in shape (short tick → `claimDue` on
`active AND next_run_at ≤ now` → handler). Kept hand-rolled: single-process, DB-
durable, and the domain forces (derive cadence, governor, coalescing, retire) don't
fit a generic scheduler cleanly. Off-the-shelf is adopted only where it removes a
hard problem — the rate limiter (Bucket4j).

## Migration / backfill

Tiny row counts; rebuild rather than transform:

1. New tables/columns; one `availability_watch_target` per existing watch's
   `poi_id`/`reservable_id`.
2. One `availability_poller` per distinct `(provider, parent_ref)` across active
   watches; link its watches; set representative `poi_id`.
3. Repoint `availability_run.poller_id` via old job→watch→parentRef; drop
   pre-migration runs with no surviving poller (`poller_id NOT NULL`, negligible).
4. Drop `availability_job`. `availability_cell` starts empty (populated on next poll).

## Surfacing (Grafana)

Rework `reservable-availability-watch-drill-down.json` to `watch →
availability_watch_poller → poller → run`, computing `window`/`cadence`/`refcount`
in the panel query (no longer columns). "Watches & Jobs" → "Watches & Pollers":
one poller row per campground with attached-watch count. Add a `availability_cell`
matrix view (reservables × dates, current status) — the cube's face.

## Testing

- Two watches on one parentRef (differing scope + non-overlapping dates) → **one**
  poller, **one** call/run (Problem regression).
- Two POIs sharing a parentRef → **one** poller (parentRef-key regression).
- Watch spanning two parentRefs → two links; each polled once.
- Cadence fall-through (watch > poi > global); poller = min over live; tighter join
  pulls `next_run_at`.
- Governor: > budget due pollers → ≤ budget fetches/interval, rest defer, no 429;
  budget survives a restart (Postgres-backed).
- Force pull: `next_run_at = now`, force-fetch, token + cooldown respected.
- Expiry: all elapsed → retire, no call, `next_run_at` frozen. Partial → clamp,
  keep polling. Reject-in-past.
- Cube: unchanged cell → `last_observed_at` bumped, **no** snapshot row; changed
  cell → cell upserted + one transition row; a `reserved → available` transition is
  detectable as the future alert trigger.

## PR sequencing

- **PR 1: pollers + coalescing (physics).** New poller/join tables (parentRef key),
  executor takes a poller and derives window/cadence, single-scope membership,
  migration, Grafana rework, `min` cadence + `GLOBAL_DEFAULT` + existing backoff.
  Snapshot writing unchanged for now. The prod fix.
- **PR 2: watch = set (intent).** Multiple `availability_watch_target` rows +
  multiple links. Touches only watch service/API/UI — **no poller/run/fetch change**,
  proving the seam.
- **PR 3: availability cube.** `availability_cell` + edge-triggered snapshots +
  cell matrix panel.
- **PR 4: cadence config + vendor governor.** `poi.cadence_override_sec` fall-through
  + Bucket4j-Postgres governor at fetch.
- **PR 5: force pull.** "Check now" route + cooldown.
- **PR 6 (out of scope here): alerts.** Trigger eval + notification over the cube;
  the `alert` noun lands here.

## Risks / open questions

- **Sparse nights over-fetch.** `window` = span `[min,max]` fetches every bucket in
  the span even if nights cluster at the ends. v1 accepts (correctness > perf);
  refinement = fetch only buckets covering the union of nights. Documented.
- **Governor fairness.** FIFO-by-`next_run_at` favors hot grounds (desired); a
  pathological fleet could starve cold pollers. Add fair-share/priority tiers if it
  bites; `log()` what was deferred (no silent cap).
- **Deriving cadence needs the live-watch join per run.** Cheap (executor loads
  members anyway); revisit only if profiling says so.
- **`availability_job_run` rename** ripples to repo/dashboard names; do it atomically
  in PR 1.
- **Multi-provider POI** would break one-parentRef-per-call-unit assumptions; not a
  case today (`poi.source` single-valued).
