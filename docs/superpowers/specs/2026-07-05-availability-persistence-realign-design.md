# Availability persistence realign: one `availability` interval table

Status: proposed
Date: 2026-07-05

## Problem

The availability persistence layer drifted from `docs/backend-architecture.md`
and the drift wasn't caught in review:

1. **A "repo" that isn't one.** `repo/AvailabilityCacheStore` runs a cross-table
   transaction (upsert `availability_cell` + append `availability_snapshot`) and
   implements cache fall-through — both service-layer concerns per the doc
   ("service owns cache fall-through", line 28). It is a service in the repo
   package.
2. **Two repos own one table.** `AvailabilityHeatmapRepo` and
   `AvailabilityCellRepo` both read `availability_cell`; the heatmap repo is a
   read projection named after a UI artifact.
3. **Storage jargon and misleading names.** `availability_cell` is a matrix,
   `availability_snapshot` is really change-history, and
   `SnapshotBackedAvailabilityService` leaks its backing store into the service
   name. `reservation-providers.md:74` still claims the read path reads the
   snapshot table, false since `fix/availability-read-from-cube`.

The root cause is deeper than naming: `availability_cell` (current state) and
`availability_snapshot` (change history) are the **same entity** — a cell's
status over time — split across two tables as an artifact of the cube fix. The
matrix's only non-derivable contribution over the history was `last_observed_at`
(liveness). Once liveness lives on the current row of a single interval table,
the split has no reason to exist.

## Principles being codified

Into `docs/backend-architecture.md`, so the next violation is caught in review:

1. **Write-ownership is 1:1.** Each table has exactly one repo that owns its
   mutations. Reads may join; multiple read-projection repos per table are
   allowed (`PoiRepo` writer + `PoiServingRepo` reader is the model).
2. **Reader vs writer is explicit in the name** — by function
   (`upsert*/insert*/update*/delete*/mark*` vs `read*/load*/find*`) or by
   class (`…ServingRepo`/`…ReadRepo`), never named after the UI feature it feeds.
3. **No cross-table transactions in `repo`.** Multi-table transactions are
   service orchestration.
4. **Storage names never climb into service names.** `matrix`, `history`,
   `interval`, `snapshot` are persistence vocabulary; a service is named for its
   job (`CachedAvailabilityService`), never its store.
5. **No port interface without multiple runtime implementations.** A test fake
   doesn't count (doc lines 299–305); extract pure logic to test fast instead.
6. **Store only non-derivable facts.** Don't persist state you can derive
   (`is_current`, `observed_from`); persist what you can't (`previous_id`).

## Data model: one `availability` table

A temporal/interval table. Each row is a **status run** for a cell, not an
observation.

```
availability(
  id                bigserial primary key,
  reservable_id     bigint      not null,
  target_date       date        not null,
  status            availability_status not null,
  last_observed_at  timestamptz not null,   -- advances in place on each confirming poll
  previous_id       bigint      references availability(id),  -- prior status-run for this cell; null for the first
  run_id            bigint                                    -- the poll run that created this row (trace)
)
```

Constraints / indexes:

- `UNIQUE(previous_id)` — a status-run has at most one successor; keeps the chain
  linear (no forks). `NULL`s allowed (multiple first-rows across cells).
- Index `(reservable_id, target_date, last_observed_at DESC)` — serves the
  current-state read and the write-path "find current" lookup.

Nothing else is stored, because everything else derives:

| Derived value | How |
|---|---|
| Current status of a cell | row with `MAX(last_observed_at)` for `(reservable_id, target_date)` |
| Liveness (`last_observed_at`, "when last checked") | that current row's `last_observed_at` |
| `observed_from` (interval start) | `previous.last_observed_at` (null if `previous_id` null) |
| `last_changed_at` | same as `observed_from` of the current row |
| Full history / timeline | walk `previous_id` back from the current row |

### Write path

- **No change** → `UPDATE` the current row `SET last_observed_at = now`. In place;
  the table does not grow on unchanged polls.
- **Status changed** → `INSERT` a new row `(status, last_observed_at = now,
  previous_id = <current row id>, run_id)`. The prior row's `last_observed_at`
  becomes the new row's derived `observed_from`.

Both are single-table operations. A transition is `find current → insert`,
wrapped in one transaction (single table — no cross-table orchestration).

### Accepted consequences

1. **Current-state read is top-1-per-group** (`DISTINCT ON (reservable_id,
   target_date) … ORDER BY last_observed_at DESC`), not a flagged point-read.
   Cheap on this sparse table (few rows per cell) with the index above; unrelated
   to the old 5.6s scan, which was a dense per-observation log. The write path
   does the same lookup to find the row to bump.
2. **First row of each cell has no `observed_from`** (`previous_id IS NULL`);
   its interval start is unknown. Acceptable.

## Refactor

### Repo layer

| Before | After |
|---|---|
| `AvailabilityCellRepo` (`availability_cell`) | `AvailabilityRepo` (`availability`) — owns all writes (`recordObservations` = bump-or-insert, `markElapsedAsPast`) and reads (`readCurrent(reservableIds, dates)`, history walk) |
| `AvailabilitySnapshotRepo` (`availability_snapshot`) | **deleted** — history is the same table now; its read methods (`listForReservable`, `listForRun`, `summarize`) reimplemented as history queries on `availability` |
| `AvailabilityHeatmapRepo` | **deleted** — read folds into `AvailabilityRepo.readCurrent` |
| `AvailabilityCacheStore` + `Impl` | **deleted** — no cross-table txn to house; read is `AvailabilityRepo.readCurrent`, write is `AvailabilityRepo.recordObservations` |
| `AvailabilitySnapshotStore` (interface) | **deleted** — test-seam port replaced by pure-logic extraction |

`AvailabilityRepo` is the single write-owner of the single table. One repo, one
table.

### Service layer

- `SnapshotBackedAvailabilityService` → **`CachedAvailabilityService`** (named for
  its job: read-through availability caching). The record-fetch logic calls
  `AvailabilityRepo.recordObservations`; the transaction is single-table and
  lives in the repo, so the service just decides *when* to fetch/record.

### Call sites

- `Main.kt` — drop the `*Store` wiring; wire one `AvailabilityRepo`.
- `ReservableAvailabilityComposer` — constructor param.
- `WatchAlertDispatcher` — `heatmaps.loadHeatmap` → `availability.readCurrent`.
- `AvailabilityWatchRoutes` — heatmap endpoint reads `readCurrent`; the
  `AvailabilityWatchHeatmap*` response DTOs stay in the route.
- `AvailabilityDashboardRoutes` — `/snapshots` + `/snapshots/summary` history
  reads move to `AvailabilityRepo` history queries.
- `AvailabilityPollExecutor` — append/prune → `AvailabilityRepo`
  (`recordObservations`, `pruneBefore`); its own two-table txn collapses to one.

## Migration + blast radius

New Flyway migration (e.g. `V36__availability_interval_table.sql`), a plain
forward schema change — **no backfill, no data preservation**:

1. Create `availability` (schema above).
2. Drop `availability_cell` and `availability_snapshot`.

Past CREATE migrations (`V17`, `V31`, …) are left untouched (Flyway history);
`V36` supersedes them. The dev database is wiped with `make reset-db`, so Flyway
replays `V1…V36` into the new schema cleanly — no cutover backfill to reason
about. The empty table self-heals on the next poll / first on-demand read.

Blast radius that must land together:

- **jOOQ regen** — new `AVAILABILITY` table; remove `AVAILABILITY_CELL` /
  `AVAILABILITY_SNAPSHOT`.
- **10 Grafana dashboards** reference the old table names and break silently
  otherwise: `availability-cell-matrix.json`, `poi-detail.json`, `db-stats.json`,
  `reservable-availability-watch-drill-down.json`, `api-sql-equivalence.json`,
  `reservable-detail.json`, `reservable-stats.json`, `poi-reservables.json`,
  `catalog-explorer.json`, `poller-run-detail.json`. Current-state panels become
  `DISTINCT ON … last_observed_at DESC`; history panels walk the interval rows.
- **Dashboard route SQL** and any raw SQL in the repos.
- Grep `availability_cell` / `availability_snapshot` across `.kt/.sql/.json/.md`.

## Testing (option b)

- Extract the pure decision logic — TTL freshness (`hasFullFreshCoverage`) and
  window coverage (`hasFullCoverage`) — as pure functions over `models`, unit
  tested without a DB.
- Test the DB path (`AvailabilityRepo` bump-vs-insert, chain integrity,
  `readCurrent`, history walk; `CachedAvailabilityService` record path) with
  Testcontainers Postgres. No port interfaces.

## Doc updates

- `docs/backend-architecture.md` — add the six principles.
- `docs/reservation-providers.md` — fix the stale line 74 and the "Availability
  history" section, which describes a separate append-only snapshot table; it's
  now interval rows on `availability`.

## Audit findings (rule (c): fix + codify + audit)

Write-ownership audit of `repo/` — one other genuine violation beyond
availability:

- **`import_runs` has two writers** (`PoiRepo` + `ReservableRepo`) → extract
  `ImportRunRepo` as sole writer. Recommended companion change (small; only other
  real write-ownership breach).
- `ReservableRepo`, `AvailabilityPollerRepo`, `AvailabilityWatchRepo` multi-table
  access is predominantly read-joins/delegation, permitted by the rule. Confirm
  each mutation has a single owner; fix any stray cross-table *write* found in
  implementation via the single-owner + service-txn pattern.

## Out of scope

- No change to poll cadence, retention policy semantics, alert edge-detection, or
  the availability status enum.
- No `pois` multi-repo consolidation — the writer/reader split there is sanctioned.
- Heatmap stays a route-layer concept; no UI/API contract change.

## Rollout order

1. Doc: codify the rule; fix the stale `reservation-providers.md` lines. (No code
   risk; sets the contract.)
2. Flyway migration: create `availability`, drop old tables (no backfill); jOOQ regen.
3. `AvailabilityRepo` (single write-owner + reads); delete
   `AvailabilityHeatmapRepo`, `AvailabilitySnapshotRepo`, `AvailabilityCacheStore`,
   `AvailabilitySnapshotStore`.
4. `CachedAvailabilityService` rename + record path; update all call sites,
   dashboard route SQL, and the 10 Grafana dashboards.
5. `import_runs` → `ImportRunRepo`.
6. Tests: pure-logic extraction + Testcontainers. Full build under the
   Gradle-provisioned corretto 21 (JDK 25 breaks the Kotlin compiler here).
