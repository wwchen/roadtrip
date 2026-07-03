# Poll-target coalescing — design

Date: 2026-07-03
Status: proposed
Related: `docs/reservation-providers.md`, `rfcs/0007-availability-search-and-alerts.md`, `docs/superpowers/specs/2026-07-02-availability-fetch-batching-and-trace-design.md`

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

The right fix is not "run once" — the two intents differ (scope **and**
non-overlapping dates) and must alert independently. The fix is to **coalesce
the upstream fetch across watches** while keeping per-watch intent intact.

## Key insight — separate intent from physics, and coalesce at set time

The concept currently conflated inside `availability_job` splits into two:

- **Intent** — what a user wants: a set of POIs/reservables, dates, cadence,
  trigger, filters, notification target. Owned entirely by the user layer.
- **Physics** — what actually gets polled: one shared observation unit per
  campground, intent-free.

Coalescing is computed **at watch set/edit time**, not per poll: when a watch is
written, we resolve its intent into the campground(s) it touches and link it to
the shared physical unit for each, ref-counted. This delivers the doc's slot
model structurally instead of re-deriving grouping every tick.

## Nomenclature (convention-aligned)

Aligned with standard monitoring/scheduler vocabulary (Prometheus scrape
target, k8s/etcd watch = subscription, Airflow run), and with the *least*
migration from today's names:

| Layer | Name | Unit / key | Grain | Owns |
|---|---|---|---|---|
| intent (N:M) | **watch** *(kept)* | `watch_id` | a **set** of POIs/reservables × date set | trigger, filters, cadence-desired, notification |
| physical (schedulable) | **poll target** | `(provider, poi)` | 1 campground × union window, one cadence | `next_run_at`, claim, window, refcount |
| execution | **run** | `run_id` | one poll firing | status, snapshot_count, duration |
| upstream request | **fetch call** | `(run, provider, parent_ref, bucket)` | one upstream call | outcome, duration |
| observation | **snapshot** | `(reservable, date, observed_at)` | one reservable × one day | status |
| *(future)* firing/event | **alert** *(reserved)* | — | one trigger firing | — |

`alert` is deliberately **not** used for standing config (that's the `watch`),
reserving it for the future firing event per Prometheus/Datadog convention.
"Job" retires as a domain noun; the generic framework nouns (`Scheduler<T>`,
`Schedulable`) stay — the availability **poll target** is the concrete
`Schedulable`.

## Model

```
POI        1 ─── N  Reservable
Watch      N ─── M  Poll target       ← coalescing edge (join + refcount)
Poll target  = the Schedulable        ← { provider, poi, window, cadence, refcount, next_run_at, claim }
Poll target 1 ─── N  Run
Run        1 ─── K  Fetch call        ← one call per provider bucket over the window
Fetch call 1 ─── (R×D)  Snapshot
```

Chain becomes **`watch → poll target → run → fetch call → snapshot`** (was
`watch → job → run → …`; `job` folded into `poll target`).

### Load-bearing invariant

> A poll target is exactly one POI on one provider. A watch decomposes into the
> set of distinct POIs its targets touch — one N:M link per POI, ref-counted.
> (Assumes one POI = one provider; true today via `poi.source`.)

### The seam (lossy projection, computed at set time)

A watch projects **down** to only `{ poi, window, cadence, refcount }` per POI
it touches. Everything else — trigger, `reservable_filters`, notification, the
reservable subset — stays in the watch and is re-applied **up** at eval time by
reading the shared snapshots. The poll target never branches on user intent.

- **window** = span `[min, max]` over the union of linked active watches'
  `target_dates`, clamped to `bookingHorizonDays`. The batcher chunks this into
  provider buckets (rec.gov months) and skips buckets with no future date.
- **cadence** = `min(cadence_sec)` over linked active watches (tightest wins;
  then rate-limit/backoff clamps it, unchanged from 07-02).
- **refcount** = number of active watch links. Lifecycle: poll target created
  lazily on first link; polling stops (`status = done`) when refcount hits 0 —
  the doc's "starts on first watch, stops at zero," now literal.

### Over-fetch is intentional and lossless

One rec.gov call over-covers every watch on that campground — it is a superset.
Snapshots record the finest grain (reservable × day), so any watch reconstructs
its exact answer by filtering. We never make a filtered or single-site upstream
call; `reservable_filters` and dates are alert-layer concerns only.

## Schema

Keep `availability_watch` as the intent table; retire `availability_job` in
favor of `availability_poll_target`; add the N:M join; repoint runs.

```sql
-- availability_watch: gains SET scope. Drop the single-scope poi_id/reservable_id
-- columns + scope CHECK; move scope into a child table. Keep dates/cadence/
-- trigger/status header fields.
CREATE TABLE availability_watch_target (
  watch_id       BIGINT NOT NULL REFERENCES availability_watch(id) ON DELETE CASCADE,
  poi_id         BIGINT REFERENCES pois(id)        ON DELETE CASCADE,
  reservable_id  BIGINT REFERENCES reservables(id) ON DELETE CASCADE,
  CHECK ((poi_id IS NOT NULL) <> (reservable_id IS NOT NULL))   -- exactly one per row
);

-- availability_poll_target: the shared schedulable (absorbs availability_job).
-- No intent_payload — window/cadence are DERIVED from linked watches and read
-- by a run at start, so an edit can't retroactively change an in-flight run.
CREATE TABLE availability_poll_target (
  id             BIGSERIAL PRIMARY KEY,
  provider       TEXT        NOT NULL,
  poi_id         BIGINT      NOT NULL REFERENCES pois(id) ON DELETE CASCADE,
  window_start   DATE        NOT NULL,
  window_end     DATE        NOT NULL,
  cadence_sec    INT         NOT NULL CHECK (cadence_sec >= 5),
  refcount       INT         NOT NULL DEFAULT 0 CHECK (refcount >= 0),
  status         TEXT        NOT NULL DEFAULT 'active'
                               CHECK (status IN ('active','paused','done')),
  next_run_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  claimed_until  TIMESTAMPTZ,
  claim_token    TEXT,
  last_run_at    TIMESTAMPTZ,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (provider, poi_id)            -- one poll target per campground per provider
);
CREATE INDEX availability_poll_target_due_idx
  ON availability_poll_target (next_run_at) WHERE status = 'active';

CREATE TABLE availability_watch_poll_target (
  watch_id        BIGINT NOT NULL REFERENCES availability_watch(id)        ON DELETE CASCADE,
  poll_target_id  BIGINT NOT NULL REFERENCES availability_poll_target(id)  ON DELETE CASCADE,
  PRIMARY KEY (watch_id, poll_target_id)
);

-- availability_job_run → availability_run: job_id becomes poll_target_id.
ALTER TABLE availability_job_run RENAME TO availability_run;
ALTER TABLE availability_run RENAME COLUMN job_id TO poll_target_id;
-- (FK re-pointed to availability_poll_target in the migration body.)
```

`availability_snapshot` and `availability_fetch_call` are **unchanged** —
`fetch_call` is already keyed at `(run_id, provider, parent_ref)`, i.e. exactly
poll-target grain.

## Membership maintenance (knob #2, resolved)

Poll-target membership is a **pure function of active watches**, recomputed
transactionally on every watch write (create / edit / pause / resume / expire /
delete), in the watch service:

1. Resolve the watch's target set → distinct POIs (reservables expand to their
   parent POI; a whole-POI target is its own POI).
2. Upsert one `availability_poll_target` per POI; insert/delete
   `availability_watch_poll_target` links to match; adjust `refcount`.
3. Recompute each touched poll target's `window` (union span) and `cadence`
   (min) from its remaining active links.
4. Poll targets whose refcount reaches 0 → `status = done`.

A **run reads the poll target's `{window, cadence}` at start** (as the executor
reads intent today), so the removed `intent_payload` freeze is preserved in
behavior without denormalized state.

## Executor changes

`AvailabilityPollExecutor.handle(pollTarget)` (was `handle(job)`):

- `resolveTargets` collapses to "all reservables under `pollTarget.poi_id`"
  (always whole-POI; the `AvailabilityJobIntent` sealed type + per-site variant
  are removed — scope now lives in watches, not the schedulable).
- Everything below — `CatalogAvailabilityBatcher.fetchByGroup`, snapshot append,
  `availability_fetch_call` trace, backoff — is **unchanged**. This is the proof
  the seam holds: coalescing changes *what schedules a run*, not the run itself.

## Migration / backfill

Row counts are tiny (a handful of watches/jobs). Rebuild rather than
transform-in-place:

1. Create the new tables; for each existing `availability_watch`, insert one
   `availability_watch_target` from its `poi_id`/`reservable_id`.
2. For each distinct POI across **active** watches, create one
   `availability_poll_target`, link its watches, set refcount/window/cadence.
3. Repoint `availability_run.poll_target_id` via the old job→watch→POI mapping
   to the POI's new poll target. Pre-migration runs whose POI has no active
   watch (thus no poll target) are dropped — `poll_target_id` is `NOT NULL`, and
   the volume is negligible. (Drop-vs-retain confirmed in the plan.)
4. Drop `availability_job`.

## Surfacing (Grafana)

`reservable-availability-watch-drill-down.json` joins `watch → job → run` today.
Rework to `watch → (availability_watch_poll_target) → poll target → run`. The
"Watches & Jobs" panel becomes "Watches & Poll targets": one poll-target row per
campground with its attached-watch count and refcount, resolving the user's
original confusion (two jobs → one poll target, two watches).

## Testing

- Two watches on one POI (differing scope + non-overlapping dates) → **one**
  poll target, **one** `catalogAvailability` call per run (regression for the
  duplication in Problem).
- A watch spanning two POIs → two poll-target links; each POI polled once.
- Watch edit that drops a POI → link removed, refcount decremented; POI with
  refcount 0 → `status = done`, no longer claimed.
- Adding a second watch to a POI with a tighter cadence → poll target cadence
  tightens; removing it relaxes.
- A run reads window/cadence at start; editing a watch mid-run does not change
  that run's fetched window.
- Snapshots for a shared poll target carry one `observed_at` per campground/date
  (no duplicate history rows across the former two jobs).

## PR sequencing

Per the multi-PR title convention. The seam lets intent-shape (sets) land
*after* physics, proving the layering:

- **PR 1: poll targets + coalescing (physics).** New tables, watch↔poll-target
  join, executor takes a poll target, membership maintenance for **single-scope**
  watches (one link each), migration, Grafana rework. Fixes the duplicate-fetch
  problem. Watches stay single-scope.
- **PR 2: watch = set (intent).** Widen a watch to multiple
  `availability_watch_target` rows + multiple links. Touches only the watch
  service/API/UI — **no poll-target, run, fetch, or snapshot change**, by design.
- **PR 3 (later, out of scope here): alerts.** Trigger evaluation + notification
  reading shared snapshots per watch. The `alert` noun lands here.

## Risks / open questions

- **Sparse date sets over-fetch.** window = span `[min,max]` fetches every
  provider bucket in the span even if members cluster at the ends (Jan + Dec →
  12 months). v1 accepts this (correctness over perf); refinement = derive the
  minimal *set* of buckets covering the union of `target_dates` and have the
  batcher fetch only those. Documented extension point.
- **Run backfill** (drop vs re-attach pre-migration runs) — resolve in the plan;
  leaning drop given volume.
- **`availability_job_run` rename** ripples to repo/dashboard/query names; do it
  in PR 1 atomically or keep the table name and only swap the FK column
  (decided in the plan).
- **Multi-provider POI** would break the one-POI-one-provider invariant; not a
  case today (`poi.source` is single-valued) — revisit if a POI ever federates.
