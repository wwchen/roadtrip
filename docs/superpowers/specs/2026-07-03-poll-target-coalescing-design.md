# Poller coalescing (watch → poller)

Date: 2026-07-03
Status: proposed
Related: `docs/reservation-providers.md`, `rfcs/0007-availability-search-and-alerts.md`, `docs/superpowers/specs/2026-07-02-availability-fetch-batching-and-trace-design.md`

> Naming note: the physical unit was called "poll target" in earlier drafts;
> renamed to **poller** (one word, cleaner DB name, no clash with the existing
> `AvailabilityTargetResolver` / `ResolvedAvailabilityTarget`). Filename kept to
> avoid churn.

## Problem

On the `reservable-watch-drill` dashboard, two watches on the **same
campground** (POI 2006, Upper Pines) run as two independent jobs and issue two
independent rec.gov fetches per cycle:

| | scope | dates |
|---|---|---|
| watch 1 / job 1 | whole POI (235 reservables) | 2026-07-06 → 07-08 |
| watch 2 / job 2 | one reservable (`site:recgov:100`) | 2026-07-01 → 07-03 |

rec.gov availability is **per-campground-per-month**: one call returns the whole
campground's month regardless of which sites or dates the caller wants. So both
jobs fetch Upper Pines July availability — two near-identical upstream calls for
data that overlaps almost completely. Today's dedup (`CatalogAvailabilityBatcher`,
per the 07-02 spec) collapses fan-out *within one run*; it never crosses job
boundaries. `docs/reservation-providers.md` already *promises* the cross-watch
version ("Free dedup across users. Two users watching the same slot share one
poll"), but the implementation's unit of work is the watch/job (1:1), not the
shared slot — so the promise is undelivered.

## Why this matters (product)

The product is: **monitor campgrounds, catch last-minute cancellations, alert
the user fast enough to grab the site.** Cancellations re-snap in seconds on hot
grounds, so tight polling is the whole value — but tight polling of popular
grounds by many users is exactly what trips a vendor's rate limit, and a 429
means we **miss the cancellation we were polling for.** Coalescing is therefore
not an optimization; it is what makes tight polling survivable. Everything below
(poller → run → fetch → snapshot) is plumbing whose only job is to make the
**alert** fast, reliable, and cheap enough to run continuously.

The fix is not "run once" — the two intents differ (scope **and** non-overlapping
dates) and must alert independently. The fix is to **coalesce the upstream fetch
across watches** while keeping per-watch intent intact.

## Key insight — separate intent from physics; coalesce at set time; derive, don't store

The concept currently conflated inside `availability_job` splits into two:

- **Intent** — what a user wants: a set of POIs/reservables, dates, cadence,
  trigger, filters, notification target. Owned entirely by the user layer.
- **Physics** — what actually gets polled: one shared observation unit per
  campground, intent-free.

Two rules make this cheap to maintain:

1. **Coalesce at set/edit time.** When a watch is written, resolve its intent
   into the campground(s) it touches and link it to the shared poller for each.
2. **Derive, don't store.** A poller row stores only *identity* + *scheduler
   state*. Its window, cadence, and refcount are **derived at run start** from
   its live watches — never persisted — so nothing drifts as time passes or
   watches change. (See *Lifecycle & derived state*.)

## Nomenclature (convention-aligned, one word each)

Aligned with standard monitoring/scheduler vocabulary (Prometheus scrape target,
k8s/etcd watch = subscription, Airflow run), with the *least* migration from
today's names:

| Layer | Name | Key | Grain | Owns |
|---|---|---|---|---|
| intent (N:M) | **watch** *(kept)* | `watch_id` | a **set** of POIs/reservables × date set | trigger, filters, cadence-desired, notification |
| physical (schedulable) | **poller** | `(provider, poi)` | 1 campground | scheduler state only (`next_run_at`, claim, status) |
| execution | **run** | `run_id` | one poll firing | status, snapshot_count, duration |
| upstream request | **fetch** | `(run, provider, parent_ref, bucket)` | one upstream call | outcome, duration |
| observation | **snapshot** | `(reservable, date, observed_at)` | one reservable × one day | status |
| *(future)* firing/event | **alert** *(reserved)* | — | one trigger firing | — |

`alert` is deliberately **not** used for standing config (that's the `watch`),
reserving it for the future firing event per Prometheus/Datadog convention.
"Job" retires as a domain noun; the generic framework nouns (`Scheduler<T>`,
`Schedulable`) stay — the availability **poller** is the concrete `Schedulable`.
`fetch` replaces "fetch call".

## Model

```
POI    1 ─── N  Reservable
Watch  N ─── M  Poller       ← coalescing edge (join)
Poller   = the Schedulable   ← stores { provider, poi, status, next_run_at, claim }; window/cadence/refcount DERIVED
Poller 1 ─── N  Run
Run    1 ─── K  Fetch        ← one call per provider bucket over the derived window
Fetch  1 ─── (R×D)  Snapshot
```

Chain becomes **`watch → poller → run → fetch → snapshot`** (was
`watch → job → run → …`; `job` folded into `poller`).

### Load-bearing invariant

> A poller is exactly one POI on one provider. A watch decomposes into the set of
> distinct POIs its targets touch — one N:M link per POI. (Assumes one POI = one
> provider; true today via `poi.source`.)

### The seam (lossy projection)

A watch projects **down** to only *which POIs it touches* (the link) + its
*dates* and *desired cadence* (read at run start). Everything else — trigger,
`reservable_filters`, notification, the reservable subset — stays in the watch
and is re-applied **up** at eval time by reading the shared snapshots. The poller
never branches on user intent, and stores none of it.

### Over-fetch is intentional and lossless

One rec.gov call over-covers every watch on that campground — it is a superset.
Snapshots record the finest grain (reservable × day), so any watch reconstructs
its exact answer by filtering. We never make a filtered or single-site upstream
call; `reservable_filters` and dates are watch-layer concerns only.

## Schema

Keep `availability_watch` as the intent table; retire `availability_job` in
favor of a lean `availability_poller`; add the N:M join; repoint runs.

```sql
-- availability_watch: gains SET scope. Drop the single-scope poi_id/reservable_id
-- columns + scope CHECK; move scope into a child table. Keep dates/cadence/
-- trigger/status header fields. cadence_sec becomes the *desired* override
-- (nullable → fall through to poi/global; see Cadence).
CREATE TABLE availability_watch_target (
  watch_id       BIGINT NOT NULL REFERENCES availability_watch(id) ON DELETE CASCADE,
  poi_id         BIGINT REFERENCES pois(id)        ON DELETE CASCADE,
  reservable_id  BIGINT REFERENCES reservables(id) ON DELETE CASCADE,
  CHECK ((poi_id IS NOT NULL) <> (reservable_id IS NOT NULL))   -- exactly one per row
);

-- availability_poller: the shared schedulable (absorbs availability_job).
-- IDENTITY + SCHEDULER STATE ONLY. No window, no cadence, no refcount, no
-- intent_payload — all derived at run start from live watches, so nothing
-- drifts with time or edits.
CREATE TABLE availability_poller (
  id             BIGSERIAL PRIMARY KEY,
  provider       TEXT        NOT NULL,
  poi_id         BIGINT      NOT NULL REFERENCES pois(id) ON DELETE CASCADE,
  status         TEXT        NOT NULL DEFAULT 'active'
                               CHECK (status IN ('active','paused','done')),
  next_run_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  claimed_until  TIMESTAMPTZ,
  claim_token    TEXT,
  last_run_at    TIMESTAMPTZ,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (provider, poi_id)            -- one poller per campground per provider
);
-- The orchestrator's hot path: "due, unclaimed, active."
CREATE INDEX availability_poller_due_idx
  ON availability_poller (next_run_at) WHERE status = 'active';

CREATE TABLE availability_watch_poller (
  watch_id    BIGINT NOT NULL REFERENCES availability_watch(id)   ON DELETE CASCADE,
  poller_id   BIGINT NOT NULL REFERENCES availability_poller(id)  ON DELETE CASCADE,
  PRIMARY KEY (watch_id, poller_id)
);

-- Per-campground cadence override (RFC 0007's deferred column; see Cadence).
ALTER TABLE pois ADD COLUMN cadence_override_sec INT
  CHECK (cadence_override_sec IS NULL OR cadence_override_sec >= 5);

-- availability_job_run → availability_run; job_id → poller_id.
ALTER TABLE availability_job_run RENAME TO availability_run;
ALTER TABLE availability_run RENAME COLUMN job_id TO poller_id;
-- (FK re-pointed to availability_poller in the migration body.)
```

`availability_snapshot` and `availability_fetch_call` are **unchanged** —
`fetch_call` is already keyed at `(run_id, provider, parent_ref)`, i.e. exactly
poller grain.

## Membership maintenance (set time)

Poller membership is a **pure function of active watches**, recomputed
transactionally on every watch write (create / edit / pause / resume / delete),
in the watch service — and it only ever touches *links*, never derived state:

1. Resolve the watch's target set → distinct POIs (reservables expand to their
   parent POI; a whole-POI target is its own POI).
2. Upsert one `availability_poller` per POI (create lazily on first link);
   insert/delete `availability_watch_poller` links to match.
3. Optional single write: if the edit introduces a *tighter* desired cadence,
   pull the poller's `next_run_at` earlier so the speed-up takes effect this
   cycle, not next (see Cadence).
4. If a poller has no remaining links, set `status = done` (a tick would also
   reap it; this is the eager path).

No window/cadence/refcount upkeep — there are no such columns.

## Lifecycle & derived state

The poller's effective state is a **pure function of its live watches and the
current date**, computed at run start; nothing is stored:

- **Live link.** A watch→poller link is *live* iff the watch is `active` **and**
  has ≥1 `target_date ≥ earliestDate` (target-local earliest bookable date from
  `AvailabilityDateResolver`). A watch whose dates have all elapsed is not live
  though nobody removed it.
- **Derived tuple** (recomputed each run from live links, never persisted):
  - `window = (min..max of live target_dates) ∩ [earliestDate, horizon]` — both
    edges move on their own: `window_start` clamps forward as today advances or
    the earliest-dated member drops; `window_end` retreats when the latest-dated
    member elapses/leaves. The batcher chunks the window into provider buckets
    and skips buckets with no future date.
  - `cadence` — resolved via the fall-through chain (see Cadence).
  - `refcount = count(live links)` — for observability/queries; not a column.
- **The scheduler tick is the reaper — no janitor.** If the derived window is
  empty for the whole poller (every member elapsed), the executor **retires
  instead of rescheduling**: mark the elapsed watches `done`, drop links, set
  `status = done`, do not advance `next_run_at`. This delivers the doc's "stops
  unconditionally when the date elapses," which the current executor does *not*
  do (it reschedules empty runs forever — a pre-existing bug this closes).
- **≤1 empty tick of lag.** A poller reaps on its first tick after its last live
  date passes; that tick makes no upstream call (null window → batcher skip), so
  the lag is harmless.
- **Creating an already-elapsed watch is rejected** via `resolveWindow`'s
  existing `BadDateWindow.StartBeforeEarliest` guard — you cannot start something
  already in the past.

## Cadence & rate — three forces on `next_run_at`

`next_run_at` is the single reconciliation point. Three forces write it, in
precedence order:

```
next_run_at = force ? now
                    : governor.clamp( now + min( resolve(w) for live w ) )
```

### 1. Target cadence — how often we'd *like* to poll (per site/alert)

The doc's fall-through chain, derived in-run (config-driven, never stored):

```
resolve(watch)  = watch.cadence_override  ??  poi.cadence_override_sec  ??  GLOBAL_DEFAULT
poller cadence  = min( resolve(w) for live w )     -- tightest live watch wins
```

- Hot ground fully booked (Upper Pines) → `poi.cadence_override_sec = 30` → poll
  hard, because cancellations are the only way in and re-snap in seconds.
- Sleepy weekend ground → no override → `GLOBAL_DEFAULT` (e.g. 5m).
- A user may still request tighter/looser per watch.

### 2. Vendor rate budget — how often we *can* poll (global per vendor)

Coalescing already cuts volume (N watches → 1 call/campground), but watching many
campgrounds still has to stay under the vendor's aggregate limit. A per-vendor
**rate governor** (in-memory token bucket, budget from config keyed by vendor)
lives in the orchestrator and **gates dispatch**: a due poller that can't get a
vendor token waits for the next tick.

- **Cadence is a target, not a guarantee.** Under budget pressure, real intervals
  stretch for everyone — graceful degradation beats a vendor ban.
- Hot grounds become due more often → naturally win more tokens (dispatch FIFO by
  `next_run_at`), so "aggressive on hot" falls out for free.
- Existing per-poller **backoff stays as the reactive safety net** (429 despite
  the governor → back that poller off). Governor = proactive; backoff = reactive.
- **No leak:** the governor knows only `vendor → budget`; call-shape stays in the
  adapter.

### 3. Force pull — poll *now* (manual override)

A "check now" (user/admin) sets `next_run_at = now` and force-fetches (bypasses
cache — the executor already passes `force = true`). It jumps the queue but still
draws a vendor token, with a small per-poller cooldown so it can't be spammed
into a 429.

## Executor changes

`AvailabilityPollExecutor.handle(poller)` (was `handle(job)`):

- Loads the poller's **live watches**, derives `window` and `cadence` from them
  (fall-through), and resolves reservables as "all reservables under
  `poller.poi_id`" — the `AvailabilityJobIntent` sealed type + per-site variant
  are removed (scope lives in watches, not the schedulable).
- `CatalogAvailabilityBatcher.fetchByGroup`, snapshot append,
  `availability_fetch_call` trace, and reactive backoff are **unchanged** —
  coalescing changes *what schedules a run*, not the run's body.
- Two new branches in the reschedule decision: (a) empty derived window → retire
  (Lifecycle); (b) `next_run_at = governor.clamp(now + cadence)` on success.

The orchestrator (`Scheduler`) is unchanged in shape: short tick → `claimDue`
(`status = active AND next_run_at ≤ now`) → handler. It gains a governor check at
dispatch. It only ever reads `{ next_run_at, status, claim }` off a poller — the
reason those are the only stored fields.

## Migration / backfill

Row counts are tiny (a handful of watches/jobs). Rebuild rather than
transform-in-place:

1. Create new tables/columns; for each existing `availability_watch`, insert one
   `availability_watch_target` from its `poi_id`/`reservable_id`.
2. For each distinct POI across **active** watches, create one
   `availability_poller` and link its watches.
3. Repoint `availability_run.poller_id` via the old job→watch→POI mapping.
   Pre-migration runs whose POI has no active watch (thus no poller) are dropped
   — `poller_id` is `NOT NULL` and volume is negligible.
4. Drop `availability_job`.

## Surfacing (Grafana)

`reservable-availability-watch-drill-down.json` joins `watch → job → run` today.
Rework to `watch → (availability_watch_poller) → poller → run`, with `window` /
`cadence` / `refcount` **computed in the panel query** from live watches (they're
no longer columns). The "Watches & Jobs" panel becomes "Watches & Pollers": one
poller row per campground with its attached-watch count, resolving the user's
original confusion (two jobs → one poller, two watches).

## Testing

- Two watches on one POI (differing scope + non-overlapping dates) → **one**
  poller, **one** `catalogAvailability` call per run (regression for the Problem).
- A watch spanning two POIs → two poller links; each POI polled once.
- Watch edit dropping a POI → link removed; poller with no live links →
  `status = done`, no longer claimed.
- **Cadence fall-through:** watch override > poi override > global; poller cadence
  = min over live watches; a tighter watch joining pulls `next_run_at` earlier.
- **Vendor governor:** with a budget of B calls/interval and > B due pollers,
  ≤ B dispatch per interval; the rest defer to the next tick (no 429).
- **Force pull:** sets `next_run_at = now`, force-fetches, respects the vendor
  token + per-poller cooldown.
- **Expiry:** all members elapsed → next tick retires (no upstream call,
  `next_run_at` not advanced). **Partial expiry:** window clamps forward, keeps
  polling. **Reject-in-past:** `StartBeforeEarliest`, nothing created.
- Snapshots for a shared poller carry one `observed_at` per campground/date (no
  duplicate history across the former two jobs).

## PR sequencing

Per the multi-PR title convention. The seam lets intent-shape (sets) and the rate
layer land *after* the core coalescing, proving the layering:

- **PR 1: pollers + coalescing (physics).** New tables, watch↔poller join,
  executor takes a poller and derives window/cadence in-run, membership for
  **single-scope** watches (one link each), migration, Grafana rework,
  `min(watch cadence)` + `GLOBAL_DEFAULT` + existing reactive backoff. Fixes the
  duplicate-fetch problem. This alone is the biggest rate-limit win.
- **PR 2: watch = set (intent).** Widen a watch to multiple
  `availability_watch_target` rows + multiple links. Touches only the watch
  service/API/UI — **no poller, run, fetch, or snapshot change**, by design.
- **PR 3: cadence config + vendor governor.** `poi.cadence_override_sec`
  fall-through; per-vendor token-bucket governor at dispatch. Essential once
  campground count grows.
- **PR 4: force pull.** "Check now" route + per-poller cooldown. Small, independent.
- **PR 5 (later, out of scope here): alerts.** Trigger evaluation + notification
  reading shared snapshots per watch. The `alert` noun lands here.

## Risks / open questions

- **Sparse date sets over-fetch.** `window` = span `[min,max]` fetches every
  provider bucket in the span even if members cluster at the ends. v1 accepts
  this (correctness over perf); refinement = derive the minimal *set* of buckets
  covering the union of `target_dates` and fetch only those. Documented
  extension point.
- **Governor fairness under sustained starvation.** FIFO-by-`next_run_at` favors
  hot grounds (desired), but a pathological fleet could starve cold pollers. If
  it bites, add per-vendor fair-share or priority tiers; log what was deferred
  (no silent cap).
- **Deriving cadence in-run needs the live-watch join every run.** Cheap (the
  executor loads members anyway), and correctness > the saved join. Revisit only
  if profiling says so.
- **`availability_job_run` rename** ripples to repo/dashboard/query names; do it
  atomically in PR 1.
- **Multi-provider POI** would break the one-POI-one-provider invariant; not a
  case today (`poi.source` single-valued) — revisit if a POI ever federates.
