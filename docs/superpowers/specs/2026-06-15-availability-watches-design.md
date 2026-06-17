# Availability watches, jobs, snapshots, and dispatches

Design doc replacing PR #224. Reshapes the entity model behind reservable
availability monitoring so each table is one thing, the polling scheduler
is a generic platform, and the boundary to the ATC companion is an
HTTP-served outbox.

## Why

PR #224 introduced `reservable_availability_pollers`,
`reservable_availability_runs`, and `reservable_availability_log`. Three
problems with the model:

- **Pollers mix three concerns** in one row: user intent (filters,
  dates, cadence, trigger actions), scheduler state (`next_poll_after`,
  `claimed_until`, `claim_token`), and lifecycle (`status`).
- **Runs are dual-purpose** via `source_kind IN ('query','poller')` —
  one table represents both ad-hoc one-shot queries and scheduled
  executions.
- **`log` is the wrong noun.** `docs/booking-providers.md` already names
  the same concept `availability_snapshots`; the PR drifted from the
  project's existing vocabulary.

Once those concerns are split, the operator-console use case (the only
audience for these admin pages today) becomes trivial: each entity has
one job, one page, one set of operator questions it answers.

## Architecture

```
┌──────────┐     ┌────────────┐
│   POI    │◄────│ Reservable │
└──────────┘     └────────────┘
                       ▲
                       │  (filters)
┌──────────────┐  ┌────┴───────────┐  ┌────────────────────┐
│ avail_watch  │─►│ avail_job      │─►│ avail_snapshot     │
│ user intent  │  │ scheduled work │  │ what we observed   │
└──────┬───────┘  └────────┬───────┘  └──────────┬─────────┘
       │                   │                     │
       │                   ▼                     │
       │          ┌────────────────┐             │
       │          │ avail_job_run  │             │
       │          │ one execution  │             │
       │          └────────────────┘             │
       │                                         │
       └─────────► ┌────────────────────┐ ◄──────┘
                   │ avail_dispatch     │
                   │ outbox to companion│
                   └────────────────────┘
                            ▲
                            │ HTTP only
                            │
                   ┌────────────────────┐
                   │ ATC companion      │  (Python + Playwright,
                   │ separate process   │   no DB access)
                   └────────────────────┘
```

Polling and ETL are **separate scheduling systems** that share one
Kotlin abstraction (`Scheduler<T : Schedulable>`). They do not share a
table — payloads, cadences, retention, and operator views differ enough
that a unified table would be a `kind` discriminator with nullable
columns, which is the leak the booking-provider port was built to
avoid.

The ATC companion is a **separate process** (Python + Playwright). It
never touches Postgres directly. The `avail_dispatch` table is internal
to the backend; the companion sees only HTTP.

## Entities

### avail_watch — user intent

One row per "keep an eye on these reservables for these dates and do
these things when something matches."

```
id                  BIGSERIAL PK
poi_id              FK → pois (nullable; mutually exclusive with reservable_id)
reservable_id       FK → reservables (nullable; mutually exclusive with poi_id)
reservable_filters  JSONB              -- when poi_id is set: which child reservables
target_dates        DATE[]             -- non-empty
min_nights          INT                -- ≥ 1
trigger_kinds       TEXT[]             -- ['atc', 'slack_notify', …]
trigger_config      JSONB              -- per-kind config (atc credentials hint, slack channel)
stop_when_triggered BOOLEAN
status              TEXT               -- 'active' | 'paused' | 'done'
created_at          TIMESTAMPTZ
updated_at          TIMESTAMPTZ
```

`poi_id` XOR `reservable_id` mirrors PR #224's scope check. Watches are
user-owned (eventually); today there is no user FK because the only
audience is the operator.

A watch creates exactly one `avail_job` (1:1). When the watch flips to
`paused` or `done`, the job's `next_run_at` is parked or the job is
deleted, respectively.

### avail_job — schedulable polling work

One row per active polling unit. This is what the scheduler claims and
hands to the worker. Jobs exist for two reasons:

1. **Backing a watch** (`watch_id IS NOT NULL`). Repeats on cadence.
2. **One-shot ad-hoc poll** (`watch_id IS NULL`). Replaces today's
   `POST /api/reservables/availability/query` semantics. Runs once and
   self-completes.

```
id                  BIGSERIAL PK
watch_id            FK → avail_watch (nullable, ON DELETE CASCADE)
intent_payload      JSONB              -- frozen snapshot of what to poll
cadence_sec         INT                -- 0 = one-shot
status              TEXT               -- 'active' | 'paused' | 'done'
next_run_at         TIMESTAMPTZ
claimed_until       TIMESTAMPTZ
claim_token         TEXT
last_run_at         TIMESTAMPTZ
created_at          TIMESTAMPTZ
updated_at          TIMESTAMPTZ
```

`intent_payload` is a flattened, scheduler-ready representation of what
to fetch. It's denormalized from the watch on purpose: editing a watch's
filters does not retroactively change what past runs polled, and the
worker should not have to read the watch table to do its job.

### avail_job_run — one execution

One row per scheduler claim that ran to completion (or failure).

```
id                BIGSERIAL PK
job_id            FK → avail_job (ON DELETE CASCADE)
status            TEXT                  -- 'started' | 'completed' | 'failed'
candidate_count   INT
snapshot_count    INT
dispatch_count    INT
error             TEXT
started_at        TIMESTAMPTZ
completed_at      TIMESTAMPTZ
```

The run *is* the execution. Drop PR #224's `source_kind` column —
provenance is now `job_id → job.watch_id` with NULL meaning ad-hoc.

### avail_snapshot — what we observed

One row per `(reservable, target_date)` per poll. Replaces
`reservable_availability_log`. Aligns the name with
`docs/booking-providers.md` lines 74, 154–169.

```
id              BIGSERIAL PK
job_run_id      FK → avail_job_run (ON DELETE SET NULL)
reservable_id   FK → reservables   (NOT NULL; replaces stringly reservable_rid)
target_date     DATE
observed_at     TIMESTAMPTZ
status          availability_status -- 'first_come' | 'reserved' | 'available' | 'closed' | 'unknown'
available       BOOLEAN
day_payload     JSONB
```

Two changes from PR #224:

- `reservable_rid TEXT` → `reservable_id BIGINT` FK. Joins are real;
  vendor renames don't break history.
- `run_id` (nullable) → `job_run_id` (nullable). Same provenance, named
  consistently with the run table.

Indexes:

- `(reservable_id, target_date, observed_at DESC)` — primary lookup
- `(reservable_id, observed_at DESC)` — recent-history scans
- `(job_run_id)` — drill-down from a run

### avail_dispatch — outbox to the companion

One row per side-effect to be performed by a downstream worker. This is
the table the ATC companion drains, but only via HTTP — the companion
never reads or writes it directly.

```
id                BIGSERIAL PK
snapshot_id       FK → avail_snapshot
watch_id          FK → avail_watch
kind              TEXT              -- 'atc' | 'slack_notify' | 'push_notify' | …
payload_version   TEXT              -- 'atc.v1'
payload           JSONB             -- self-contained for the companion
status            TEXT              -- 'pending' | 'claimed' | 'sent' | 'failed_retry' | 'failed_terminal' | 'skipped'
attempt_count     INT
max_attempts      INT
next_attempt_at   TIMESTAMPTZ
claim_token       TEXT
claimed_until     TIMESTAMPTZ
response_payload  JSONB             -- written on terminal success
last_error        TEXT
created_at        TIMESTAMPTZ
completed_at      TIMESTAMPTZ
```

Why an outbox table exists at all: the companion is in a different
language and process, can fail in colorful ways (CAPTCHA, hangs, browser
crashes), and may scale independently of the backend. A persistent queue
with explicit lease/retry semantics is the only contract that survives
those failure modes; in-memory queues and direct webhook callbacks do
not.

`payload` is self-contained — the companion never has to read `watch`,
`snapshot`, or `reservable` to do its job. `payload_version` lets the
companion reject unknown shapes instead of silently mis-parsing.

## Scheduler abstraction

```kotlin
interface Schedulable {
    val id: Long
    val nextRunAt: Instant
    val claimedUntil: Instant?
}

interface SchedulableRepo<T : Schedulable> {
    fun claimDue(now: Instant, leaseUntil: Instant, limit: Int, token: String): List<T>
    fun renewClaim(id: Long, token: String, until: Instant): Boolean
    fun release(id: Long, token: String, nextRunAt: Instant): Boolean
    fun reclaimExpired(now: Instant): Int   // boot recovery
}

class Scheduler<T : Schedulable>(
    private val repo: SchedulableRepo<T>,
    private val handler: suspend (T) -> Unit,
    private val leaseDuration: Duration,
    private val tickInterval: Duration,
)
```

Two instances at boot today:

- `Scheduler<AvailabilityJob>` — claims jobs, calls
  `AvailabilityPollService.execute(job)`, which writes one
  `avail_job_run` and N `avail_snapshot` rows, optionally enqueues
  `avail_dispatch` rows.
- `Scheduler<IngestRun>` — eventual migration target; out of scope for
  this design.

The scheduler is **not** used for `avail_dispatch`. Dispatches are
served over HTTP to the external companion; their claim/lease logic
lives behind the dispatch HTTP endpoints, not behind the in-process
scheduler loop.

## HTTP API

Designed to match user intent (FE pages), not table shape. Internal
Kotlin services (`AvailabilityWatchRepo`, `AvailabilityJobRepo`,
`AvailabilitySnapshotRepo`, `AvailabilityDispatchRepo`) are reusable
across routes; not every internal method is exposed.

### Watches (FE intent surface)

```
GET    /api/availability/watches
POST   /api/availability/watches
GET    /api/availability/watches/{id}
PATCH  /api/availability/watches/{id}             -- pause/resume/edit
DELETE /api/availability/watches/{id}
```

Creating a watch atomically creates its backing job. PATCH-ing
`status:'paused'` parks the job's `next_run_at`; resuming restores it.

### One-shot ad-hoc poll

```
POST   /api/availability/poll
       body: { poi_id|reservable_id, target_dates, … }
       resp: synchronous AvailabilityResult
```

Internally creates a job with `cadence_sec=0`, `watch_id=NULL`, runs it
inline, returns the result. Replaces today's
`POST /api/reservables/availability/query`.

### Snapshots (read-only history)

```
GET /api/availability/snapshots?reservable_id=&from=&to=
GET /api/availability/snapshots?poi_id=&date=
```

Provider-agnostic; reads the snapshot table directly.

### Operator console

```
GET /api/availability/jobs?status=&due_within=
GET /api/availability/jobs/{id}
GET /api/availability/jobs/{id}/runs
GET /api/availability/runs/{id}                    -- run detail + its snapshots
GET /api/availability/dispatches?kind=&status=&watch_id=
GET /api/availability/dispatches/{id}
```

### Companion-facing dispatch endpoints

Auth: service token bound to the companion. All endpoints assume one
companion process per `kind`; concurrent claim is supported via
claim_token but not actively used by today's single ATC worker.

```
POST /api/dispatches/claim
     body: { kind, max, lease_sec }
     resp: 200 [{ id, kind, payload_version, payload, claim_token }]

POST /api/dispatches/{id}/heartbeat
     body: { claim_token, lease_sec }
     resp: 204 | 409 (token mismatch / lease expired)

POST /api/dispatches/{id}/complete
     body: { claim_token, response }
     resp: 204 | 409

POST /api/dispatches/{id}/fail
     body: { claim_token, error, retry: bool }
     resp: 204 | 409
```

`/fail` with `retry:true` schedules a retry via exponential backoff up
to `max_attempts`, after which the row transitions to
`failed_terminal`. The companion never sees expired-lease semantics
directly — it just gets `409` on `heartbeat`/`complete`/`fail` and
moves on; the backend reclaims via boot recovery.

## Pages

```
/pois               POI catalog (existing)
/reservables        Reservable catalog (existing)
/watches            Replaces /pollers. Create/list/pause/resume/cancel.
/availability       Replaces /logs. Operator dashboard with tabs:
                      jobs       — queue state, due / claimed / recent
                      runs       — execution log, filterable
                      snapshots  — observed availability, filterable
                      dispatches — outbox to companions, status + retries
/ingest-runs        ETL run dashboard (where it lives today; unchanged)
```

Drill-downs follow FK chains:

- POI → child reservables → watches that cover them → recent runs → snapshots
- Reservable → snapshot timeline
- Watch → its job → recent runs → snapshots → dispatches
- Snapshot → its run → its job → its watch (full provenance)
- Dispatch → its snapshot → its watch (why this fired)

## Migration plan from PR #224

PR #224 will not be merged. New schema lands as a fresh set of
migrations on top of `V13__reservable_availability_monitors.sql`:

```
V14__avail_watches.sql        -- rename + reshape monitors → watches
V15__avail_jobs.sql            -- new jobs table; move scheduler columns from V13/V14
V16__avail_runs.sql            -- new runs table (drop source_kind)
V17__avail_snapshots.sql       -- rename log → snapshot; add reservable_id FK; backfill
V18__avail_dispatches.sql      -- new outbox table
```

Existing `reservable_availability_log` rows backfill `reservable_id` by
joining `(type, vendor, vendor_id)` against `reservables`. Rows that
don't match are dropped (the only existing rows are dev data).

## Out of scope

- **End-user FE** for watches. The audience here is the operator; a
  user-facing "manage your alerts" UI is a layer on top of the same
  watch table when it lands.
- **ETL migration to the shared scheduler.** Touched by the abstraction
  shape but `ingest_runs` keeps its current code path until a separate
  pass.
- **Notification channels beyond ATC.** Slack/push are listed as
  example dispatch kinds for shape only; their adapters land later.
- **Cadence overrides per campground / per watch.** Mentioned in
  `docs/booking-providers.md` as deferred; still deferred here. The
  resolver will plug in without changing call sites.
- **Dispatch deduplication across watches.** Two watches that match the
  same snapshot today produce two dispatches. If that becomes a
  problem, dedup goes on the dispatch insert path; the table shape
  doesn't change.
