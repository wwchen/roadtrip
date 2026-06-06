---
title: Ingestion controller — observability + remote trigger for the existing scripts
authors:
  - William Chen
created: 2026-06-06
last_updated: 2026-06-06
rfc_pr: TBD
status: Draft
---

# Proposal: Ingestion controller — observability + remote trigger for the existing scripts

## Summary

POI ingestion is invisible: a fetcher 503ing or returning truncated data leaves
no trace except whatever terminal ran it, and the only way to refresh prod is
ssh + manual `make` targets. The Kotlin importer (`Importer.kt`) already records
runs in `import_runs` for the import half, but the *fetch* half — seven Python
scripts — is unrecorded.

This RFC adds an **observability + remote trigger layer** around the existing
ingestion stack: an `ingest_runs` table that records fetch + import + enrich
phases as one unit, an auth-gated admin API (`POST /api/admin/ingest/{target}`)
that runs the existing scripts as subprocesses with structured logging, and a
target-level lock so the multiple scripts that share `campgrounds.geojson`
can't interleave.

**This RFC does not port the Python fetchers to Kotlin yet, but porting is
the stated north star.** A single-stack, single-language ingest pipeline is
the maintainability win we're working toward — fewer Docker images, one HTTP
client, one logger, one error path. An earlier draft tried to do the port in
one RFC; Codex review surfaced three load-bearing problems with that
approach (the importer overwrites enriched properties on every upsert; the
prod data dir is mounted read-only; the campgrounds pipeline is one
composite artifact, not separable sources). Each of those is its own
non-trivial design problem.

So this RFC is the **stepping stone**: lock in the run-record schema, the
target abstraction, the per-target lock, and the admin trigger. Once those
are in place, porting any single script becomes a one-target swap
(`Phase.Shell` → `Phase.Kotlin` + a new `Source`) with no schema or API
change. The migration table at the bottom names which scripts to port and
in what order, with the precondition each port has to clear first.

## Motivation

The actual pain points, ordered:

1. **No remote trigger.** Refreshing prod data means: ssh the deploy box, pull
  the repo, run a Python script, run the importer. Nothing wrong with shell —
   the issue is there's no way to do it from anywhere else (Tilt, GitHub
   Action, browser).
2. **No failure visibility.** A fetcher returns 503, partial JSON, or 429
  rate-limit; the only trace is whatever terminal ran it. The importer then
   runs against stale data and "succeeds." `pois.fetched_at` is
   `file.lastModified()` — it lies.
3. **Concurrent writes can corrupt `campgrounds.geojson`.** Four scripts write
  it: `fetch_campgrounds.py`, `fetch_bc_parks.py`, `fetch_parks_canada.py`,
   `enrich_campgrounds.py`. There's nothing preventing two from racing if a
   future GH Action or Tilt button fires them in parallel. The single-machine,
   one-human-at-a-keyboard guarantee is not a property we want to rely on
   forever.
4. **Tilt friction.** `make pois-import` is interactive (fzf), so it can't be
  a Tilt button. Refresh is in a separate terminal.

What this RFC does **not** try to solve:

- The fact that ingestion is two languages (Python + Kotlin). That's a
consequence, not a cause. Kotlin doesn't make 503s more visible; structured
run records do.
- Cron / scheduled cadence. Manual or GH-Action-driven cadence is fine.
- Per-script error handling. The scripts already retry; this RFC just
records the outcome.

## Goals

1. **Every refresh, fetch or import, leaves a queryable record.** `ingest_runs`
  row per `(target, phase)` with started_at / completed_at / status / counts /
   notes / structured stderr tail.
2. **Remote-triggerable from anywhere we can curl.** Auth-gated `POST` admin
  endpoint that returns a `run_id` immediately and runs the work in the
   background.
3. **Targets, not sources.** A "target" is a unit of refresh that produces a
  coherent on-disk artifact. `campgrounds` (which is the composite of
   uscampgrounds + bc-parks + parks-canada + recgov-enrichment) is one target;
   so are `planet-fitness`, `state-parks`, `national-parks`, `tesla-pricing`.
   Per-target lock — concurrent `POST`s for the same target return 409.
4. **Tilt button per target.** Replaces the carve-out for the interactive
  picker.
5. **No big rewrite.** Each script keeps working as-is. The admin handler
  shells out to the same script the human shells out to today. Switching any
   given script to Kotlin later is a per-target swap with no schema change.

## Non-goals

- Porting Python fetchers to Kotlin (deferred — see Rationale).
- Porting the rec.gov enricher to Kotlin (deferred — same reason; also
blocked by the import-overwrites-enrichment problem; see Future work).
- Porting Tesla supercharger refresh (curl-impersonate Akamai bypass; out of
scope — see Future work).
- Cron scheduling, multi-instance ingestion, multi-tenant auth, audit log.
- Replacing `Importer.kt`'s mark-and-sweep semantics. The current
non-transactional behaviour is preserved (see Edge cases — partial commit).
- Writing fetched data anywhere other than `data/` on the runner host. The
prod container's `/app/static/data` is read-only by design (`docker-compose.yml:62`).
Refreshes happen on the deploy host (`./data` writable to compose) before
the container reads them.

## Proposal

### Architecture

```
                            POST /api/admin/ingest/{target}
                                       │  (access gated upstream by
                                       │   Cloudflare Zero Trust on the
                                       │   /api/admin/* path)
                                       ▼
                               ┌────────────────────────┐
                               │ AdminIngestRoutes.kt   │  per-target Mutex
                               │                        │  202 + run_id
                               └───────────┬────────────┘
                                           │ launches IO coroutine
                                           ▼
                               ┌────────────────────────┐
                               │   IngestController     │
                               │  (resolve target →     │
                               │   ordered phase list)  │
                               └────────┬───────────┬───┘
                                        │           │
                       ┌────────────────┘           └─────────────────┐
                       ▼                                              ▼
              ┌─────────────────┐                            ┌────────────────┐
              │ shell phase     │   ProcessBuilder          │ kotlin phase   │
              │   stdout→logger │   line-stream stdout/err  │  Importer.run()│
              │   exit code →   │   capture stderr tail     │  (existing)    │
              │   row.status    │                            │                │
              └────────┬────────┘                            └────────┬───────┘
                       │                                              │
                       └──────────────────┬───────────────────────────┘
                                          ▼
                      ┌──────────────────────────────────────────┐
                      │  ingest_runs table (NEW, V4__ingest.sql) │
                      │  one row per (target, phase, attempt)    │
                      └──────────────────────────────────────────┘
                                       ▲
                                       │ GET /api/admin/ingest/runs?target=...
                                       │
                                ┌──────┴──────┐
                                │ Tilt button │   one local_resource per target
                                │ Make shim   │   `make pois-refresh TARGET=...`
                                │ GH Action   │   curl admin endpoint
                                └─────────────┘
```

The repo layer is already shared — `Importer` writes `pois`, `PoiRoutes` reads
`pois`. This RFC adds a *parallel* table (`ingest_runs`) for run records and
a thin process-orchestration layer; nothing about how POI rows are stored or
served changes.

### Targets and phases

A target has an ordered list of phases. Each phase is either `shell` (run an
existing script as a subprocess) or `kotlin` (call `Importer.run(source)`).
A phase failure aborts the rest of the target's phases and marks the
target's run failed.


| Target                 | Phases (in order)                                                                                                                                           |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `planet-fitness`       | `shell:fetch_planet_fitness.py` → `kotlin:Importer(osm-pf)`                                                                                                 |
| `state-parks`          | `shell:fetch_parks.py --layer state-parks` → `kotlin:Importer(state-parks)`                                                                                 |
| `national-parks`       | `shell:fetch_parks.py --layer national-parks` → `kotlin:Importer(national-parks)`                                                                           |
| `campgrounds`          | `shell:fetch_campgrounds.py` → `shell:fetch_bc_parks.py` → `shell:fetch_parks_canada.py` → `shell:enrich_campgrounds.py` → `kotlin:Importer(uscampgrounds)` |
| `parks-canada-curated` | `kotlin:Importer(parks-canada)` (no fetch — curated JSON file)                                                                                              |
| `alberta-provincial`   | `kotlin:Importer(alberta-provincial)` (curated JSON file)                                                                                                   |
| `tesla-pricing`        | `shell:make rebuild-superchargers` (cache-only refresh; full refresh stays in Make for the egress-IP-bound case)                                            |


Targets are defined in `IngestController` as a static map. New scripts /
sources are added by appending an entry — no admin-API change, no schema
change.

The `campgrounds` target is the load-bearing case. Today, the four scripts
that touch `campgrounds.geojson` are run in sequence by hand. This RFC
encodes that sequence — and locks it under a single target — so racing is
impossible.

### Database — `V4__ingest.sql`

```sql
CREATE TABLE ingest_runs (
    id              BIGSERIAL PRIMARY KEY,
    target          TEXT NOT NULL,                -- 'campgrounds', 'planet-fitness', ...
    phase           TEXT NOT NULL,                -- 'fetch_campgrounds.py', 'kotlin:Importer(osm-pf)'
    phase_kind      TEXT NOT NULL,                -- 'shell' | 'kotlin'
    parent_run_id   BIGINT REFERENCES ingest_runs(id),  -- target-level row aggregates phase rows
    status          TEXT NOT NULL,                -- 'started' | 'completed' | 'failed' | 'aborted'
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ,
    exit_code       INT,                          -- shell phases only
    counts          JSONB NOT NULL DEFAULT '{}'::jsonb,  -- {seen, swept, candidates, enriched, ...}
    notes           TEXT,                         -- failure reason, stderr tail (last 4kb)
    triggered_by    TEXT NOT NULL,                -- 'admin-api' | 'tilt' | 'cli' | 'cron'
    CONSTRAINT ingest_runs_phase_kind_check CHECK (phase_kind IN ('shell', 'kotlin'))
);
CREATE INDEX ingest_runs_target_idx ON ingest_runs (target, started_at DESC);
CREATE INDEX ingest_runs_parent_idx ON ingest_runs (parent_run_id);
```

Rationale for a new table rather than overloading `import_runs`:

- `import_runs` is per-source and tied to `Importer.run()`. Phases like
`shell:fetch_campgrounds.py` aren't an `Importer` run.
- The campgrounds target has 5 phases for 1 import; mapping that into
`import_runs` would require either repurposing `notes` (Codex finding #12)
or making the per-source rows cluster.
- `import_runs` stays the per-Importer-run audit table, unchanged. The new
`ingest_runs` row for a `kotlin:Importer(...)` phase carries `counts ->> 'import_run_id'` to join across.

### Admin API

```
POST   /api/admin/ingest/{target}        202; { "run_id": 123 }
GET    /api/admin/ingest/runs            200; { "runs": [...] }   # 50 most recent
GET    /api/admin/ingest/runs?target=X   200; filtered
GET    /api/admin/ingest/runs/{id}       200; { id, status, phases: [...] }
GET    /api/admin/ingest/health          200; per-target last_completed + age
```

**No application-level authn.** Access is gated upstream by a Cloudflare Zero
Trust path rule on `/api/admin/*` (existing tunnel; same mechanism that already
fronts the deploy). What the admin endpoints expose:

- **Trigger** an idempotent refresh — every script is already designed to be
  re-run safely (mark-and-sweep, resume-on-`enriched: true`, file-cache TTLs).
- **Read** ingest run status — counts, exit codes, stderr tails. None of this
  is sensitive data; it's "did the Overpass call succeed."

A leaked path would let someone trigger a refresh; the worst they can do is
burn upstream rate-limit budget that we'd burn anyway on the next
`make pois-refresh`. ZT auth is the right boundary for "single dev, single
deploy, public-facing URL" — in-app token plumbing was extra moving parts for
no risk reduction.

Local dev runs without ZT in front, so `/api/admin/*` is reachable on
`127.0.0.1:8765` directly. Tilt buttons curl it. If you ever expose the dev
backend to the internet (port-forwarding for testing), bind admin routes to
loopback only — flagged in README.

The `POST` returns `202 + run_id` immediately. The work runs on a detached
`Dispatchers.IO` coroutine. The per-target `Mutex` is held for the duration
of the run; a second `POST` for the same target while one is running gets
`409 Conflict, run_id=<existing>`.

### IngestController + phase execution

```kotlin
data class Target(val name: String, val phases: List<Phase>)
sealed interface Phase {
    val label: String
    data class Shell(override val label: String, val cmd: List<String>) : Phase
    data class Kotlin(override val label: String, val sourceName: String) : Phase
}

class IngestController(
    private val ctx: DSLContext,
    private val importer: Importer,
    private val dataDir: File,
    private val workingDir: File,           // repo root for shell phases
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val targets: Map<String, Target> = ...   // static map above
    private val locks: Map<String, Mutex> = targets.mapValues { Mutex() }

    suspend fun startRun(targetName: String, triggeredBy: String): Long {
        val target = targets[targetName] ?: throw NotFoundException()
        val mutex = locks[targetName]!!
        if (!mutex.tryLock()) throw ConflictException(currentRunId(targetName))
        val parentId = createParentRow(target.name, triggeredBy)
        scope.launch(dispatcher) {
            try { runPhases(target, parentId) } finally { mutex.unlock() }
        }
        return parentId
    }

    private suspend fun runPhases(target: Target, parentId: Long) {
        for (phase in target.phases) {
            val phaseId = createPhaseRow(parentId, target.name, phase)
            try {
                val counts = when (phase) {
                    is Phase.Shell  -> runShell(phase, phaseId)
                    is Phase.Kotlin -> runKotlin(phase)
                }
                completePhase(phaseId, counts)
            } catch (e: Exception) {
                failPhase(phaseId, e)
                failParent(parentId, "phase=${phase.label}: ${e.message}")
                return
            }
        }
        completeParent(parentId)
    }
}
```

`runShell` uses `ProcessBuilder`; line-streams stdout to the logger; captures
stderr to a 4KB ring buffer that lands in the row's `notes` on failure.
`runKotlin(phase)` calls `Importer.run(sourceFor(phase.sourceName, dataDir))`
— same path as today's `gradle importer --args=...`.

### Tilt + Make integration

Tilt: one `local_resource` per target under the `data` cluster, replacing
the current scattering of refresh/import buttons. Each:

```python
local_resource(
    'refresh-campgrounds',
    cmd='scripts/admin-curl.sh ingest campgrounds',
    auto_init=False,
    trigger_mode=TRIGGER_MODE_MANUAL,
    labels=['data'],
)
```

`scripts/admin-curl.sh` POSTs (locally `127.0.0.1`; in prod via the ZT tunnel
that already authenticates the caller), polls `GET /runs/{id}` until the
parent row is `completed` or `failed`,
exits with the parent status. ~30 lines of bash; no fzf, no TTY required.

Make: `make pois-refresh TARGET=campgrounds` calls the same script. Replaces
the interactive `pois-import-picker.sh` flow. The picker file can stay if you
want fzf at the prompt, but it's no longer privileged — it just shells the
same curl.

### Per-target lock semantics

The lock is on the *target*, not the source. Targets that share an artifact
go in the same target (campgrounds — 4 phases, 1 lock). Targets with
independent artifacts get independent locks (planet-fitness vs state-parks
can run concurrently).

Concurrent admin POSTs:

- Same target → `409 Conflict, run_id=<existing-running-id>`.
- Different targets → both run; up to N locks held concurrently. With
HikariCP `maxPoolSize=4`, kotlin phases serialize on the connection pool;
shell phases hold no DB connection. Practical concurrent ceiling:
4 kotlin phases or arbitrary shell phases in flight. (Codex finding #9.)

### Edge cases

1. **Backend restart mid-run.** Phase rows in `started` for >30 minutes get
  marked `aborted` on boot, with `notes='boot recovery; phase orphaned'`.
   Pois data may have been partially upserted by a Kotlin phase — see #2.
2. **Partial upsert (Codex finding #2).** `Importer.run()` is **not**
  transactional today (Hikari autocommit, no `ctx.transaction`). This RFC
   does NOT change that — wrapping the whole import in one transaction would
   hold a connection for the full ~10s of upsert + sweep, blocking
   `PoiRoutes`. The mark-and-sweep design tolerates partial upserts: rows
   get `last_seen_run_id` stamped per upsert, and if the run aborts before
   sweep, the table just has slightly-newer data than the previous run. A
   subsequent successful run sweeps any rows that fell out of upstream.
   This is documented behaviour, not a regression.
3. **Tripwire trip.** `Importer` throws; the kotlin phase row goes to
  `failed` with the tripwire reason in `notes`; the parent run goes
   `failed`. Subsequent phases skipped.
4. **Shell exit non-zero.** Shell phase row → `failed` with exit_code +
  stderr tail in `notes`. Parent → `failed`. Subsequent phases skipped.
5. **Shell hang.** Per-phase wall-clock timeout (default 30 min, override
  per phase). On timeout, kill the process tree (`Process.descendants()`
   on JDK 9+) and mark the phase `failed`. The enricher's worst-case run
   today is ~10 min, so 30 min is comfortable.
6. **Read-only data mount in prod (Codex finding #4).** This RFC does NOT
  propose running the admin API on the deploy box. It runs on a host where
   `data/` is writable: today, that's the local dev machine and the
   deploy-via-ssh runner host. The deploy box's read-only mount is
   preserved — admin runs happen on the host filesystem before
   `docker-compose up`. The README is updated to make this boundary explicit.
   Future work: a separate "ingest worker" container with its own writable
   data volume + volume-mounted into the backend read-only.
7. `**enrich_campgrounds.py` overwrite-on-import (Codex finding #1).**
  Today the enricher writes back to the file BEFORE the importer reads it,
   so `pois.properties` lands with `enriched: true`. This RFC keeps that
   ordering — the campgrounds target's phase list is `fetch → fetch BC →  fetch Canada → enrich → import`. Import is the last phase, and reads
   the already-enriched file. **A future "port enricher to Kotlin" step
   inherits this constraint and is called out as a Future-work item with
   the design problem stated.**
8. **Stale `lastModified()` -> `fetched_at` (motivation #2).** This RFC
  doesn't fix it. Adding `--fetched-at` to each script is a small
   per-script PR; it's listed as a follow-up TODO, not part of this RFC's
   contract.
9. **Importer non-atomic upserts (Codex finding #2) — re-examined.** The
   prior justification for keeping `Importer.run()` non-transactional was
   "wrapping in one transaction holds a connection for the full ~10s of
   upsert + sweep." Given correctness > fetch perf for this codebase, that
   justification is weak: blocking `PoiRoutes` for ~10s during a manual
   admin refresh is acceptable, and the alternative (partial state on
   crash, surfaced only as "row counts look weird next sweep") is worse.
   This RFC still doesn't change `Importer` — the change is out of scope
   here — but adds it to Future work as a real port, not a "if it ever
   matters" defer.

### Test plan

**Unit:**

- `IngestController.startRun` — mutex held while running, released on
success and on failure.
- `IngestController.startRun` — second call same target → `ConflictException`.
- `runShell` — stdout streamed; non-zero exit → `failed`; stderr tail
captured; timeout kills process.
- `runKotlin` — `Importer.run` thrown exception lands in `failed` row with
the message in `notes`.
- Phase ordering — given a target with 3 phases, phase 2 failing means
phase 3's row never created.
- Boot recovery — fixture inserts a `started` row >30 min old; on boot,
it's marked `aborted`.

**Integration:**

- POST `/api/admin/ingest/planet-fitness` with valid token → 202; row
exists; subsequent GET reflects status transitions.
- POST without token / wrong token → 401.
- POST while one is running same target → 409 with `run_id` of the running
one in body.
- POST campgrounds → all five phases recorded with parent-child link;
phase 3 (parks-canada) made to fail → phases 4,5 not run; parent row
failed.
- Two different targets concurrent → both succeed; lock contention =
zero.

**Manual end-to-end:**

- Tilt button on each of the 6 targets — observe row in `ingest_runs`,
watch logs in Tilt's right pane.
- `scripts/admin-curl.sh ingest campgrounds` from a terminal — same.

**Destructive cases (Codex finding #13):**

- Concurrent writers to `campgrounds.geojson` — verified impossible by
the per-target lock; encoded in test "two POSTs against `campgrounds`
serialize."
- Read-only data mount — verified by manual test on a docker-compose
deploy: a misconfigured admin run that tries to write inside the
container fails with EROFS; production mount is unchanged.
- Restart during import after partial upserts — covered by #2 above;
test asserts the boot-recovery sweep doesn't touch `pois`, only
`ingest_runs.status`.
- Fetch failure before importer would run — phase 1 fails; phase 2 +
later not started; test asserts.

### Capacity / sizing notes

Fetch latency is not a design constraint — these are manual refreshes for a
personal project, not a hot path. Two practical sizing points:

- HikariCP `maxPoolSize=4`. Concurrent kotlin phases serialize on connections;
  campsite poller + `PoiRoutes` share the same pool. Bump to 8 in the same
  migration that adds `ingest_runs`. Cheap insurance, not a bottleneck today.
- `ingest_runs` grows ~5 rows per `make pois-refresh TARGET=all`. At
  one-refresh-per-week cadence, ~250 rows/year. No retention policy in v1;
  revisit if/when the table reaches 100k rows.

## Rationale

### Why wrap-and-observe, not port-to-Kotlin

The earlier draft of this RFC proposed porting all 7 fetchers + the
enricher to Kotlin. The pivot came from a Codex review that surfaced
three load-bearing flaws:

1. The importer overwrites `properties` on every upsert, so a Kotlin
  enricher that mutates `pois.properties` directly gets wiped on the
   next import. The Python enricher works only because it writes the
   *file* before the importer runs.
2. Production mounts `data/` read-only, so the proposed
  "fetch → cache to data/ → import" admin flow can't run in prod.
3. The per-source mutex doesn't actually protect anything because four
  "sources" share `campgrounds.geojson`. The per-PR reversibility claim
   collapsed for the same reason.

A single-language ingest stack IS the maintainability target — fewer Docker
images, one HTTP client, one logger, one error path. But each script's port
needs the run-record + target-lock seams in place first, and at least one
script (the rec.gov enricher) needs a separate fix to `pois` schema before
it can be ported at all. So the sequencing is:

1. **This RFC** — observability + remote trigger + per-target lock. Scripts
   run unchanged but become observable units.
2. **Per-script ports** — one PR per script, in priority order (see Future
   work). Each is a `Phase.Shell` → `Phase.Kotlin` swap plus a new `Source`
   class. No schema or API change.
3. **Schema/atomicity fixes** — Importer transactionality, separate
   `source_properties` from `enriched_properties` so a Kotlin enricher can
   mutate without being wiped on next import. Each is its own RFC.

Wrap-first means at every PR boundary the system works, the run records
look the same, and the lock semantics don't change. Porting in one big-bang
RFC required solving #1, #2, #3 simultaneously and committed to one
specific design for each before we'd seen any of them under load.

### Layer search

- **[Layer 1]** ProcessBuilder + structured row capture is the boring,
proven shape for "wrap a CLI in an admin API." No new framework.
- **[Layer 1]** kotlinx.coroutines `Mutex.tryLock` is the right tool for
per-target locking; resist Quartz, Redis, or `synchronized`.
- **[Layer 1]** Cloudflare Zero Trust path rule on `/api/admin/*` — auth
lives at the edge, not in the app. Single deploy, single user, idempotent
workload, non-sensitive read surface; in-app token would be moving parts
for no risk reduction.
- **[Layer 2]** `JDK 9+ Process.descendants()` for kill-on-timeout. Tested,
not exotic.
- **[Layer 3 / EUREKA]** Two things are true at once: (a) a single-language
ingest stack IS the eventual win, because two languages = two HTTP clients,
two retry stories, two log streams; (b) you cannot get there in one RFC,
because the prerequisites (schema, atomicity, prod data mount) are each
their own design problem. The trap of the first draft was conflating those
two truths into a single big-bang plan. The fix is to treat the wrap as
*scaffolding for the port*, not as the destination.

### Why not the rejected alternatives

- **Port everything to Kotlin (the prior draft).** Killed by Codex's
three findings above; scope was 10-15 files, weeks of work, with no
observability gain over wrapping.
- **Half-port (small clean sources only).** Smaller but introduces
language fragmentation without solving observability for the campgrounds
cluster — exactly the target where observability matters most.
- **Cron / scheduler integration in this RFC.** `schedules` table from
RFC 0001 could drive ingest, but adding it now widens the risk surface
and conflates "make refresh observable" with "automate refresh." Manual
cadence is fine until it isn't.
- **Wrap scripts in Python orchestrator instead of Kotlin.** The Kotlin
backend is the only long-running process we already have. Adding a
Python daemon (or invoking via cron from outside) means a second admin
surface, second auth, second log stream. Same trap the RFC is trying to
exit.

## Future work — staged plan toward single-stack ingest

The destination is one language for ingest. Wrap is scaffolding. Order:

| #   | Item                                                                                                          | Precondition                                                                                                                                       | Why this order                                                                                                                                                              |
| --- | ------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | This RFC — `ingest_runs`, target lock, admin API, sync POST                                                   | None                                                                                                                                               | Establishes the seams. Every later step plugs in without changing the contract.                                                                                             |
| 2   | `--fetched-at` flag on each Python script; importer reads it instead of `file.lastModified()`                 | Step 1                                                                                                                                             | Fixes `pois.fetched_at` lying. ~1hr per script, can ride along step 4.                                                                                                      |
| 3   | Port `fetch_planet_fitness.py`, `fetch_parks.py` (state-parks, national-parks) → Kotlin `HttpSource`          | Step 1                                                                                                                                             | Cheapest ports. Single HTTP call each, no shared file with anything. Validates the swap pattern under real load.                                                            |
| 4   | Port `fetch_campgrounds.py` (uscampgrounds CSV) → Kotlin `HttpSource`                                         | Step 3                                                                                                                                             | Bigger transform but still owns its file end-to-end (BC + parks-canada *merge into* campgrounds.geojson — they don't replace it).                                           |
| 5   | Port `fetch_bc_parks.py`, `fetch_parks_canada.py` → Kotlin                                                    | Step 4                                                                                                                                             | These mutate the file uscampgrounds creates. Needs a clean composite-source design first; Kotlin uscampgrounds gives us the seam.                                           |
| 6   | Make `Importer.run()` transactional                                                                           | Step 5                                                                                                                                             | Holding a connection for ~10s during admin refresh is fine (correctness > fetch perf). Required before enricher port so the enricher can rely on import being all-or-nothing. |
| 7   | Schema split: `pois.source_properties` (overwritten on import) vs `pois.enriched_properties` (preserved)      | Step 6                                                                                                                                             | Prereq for porting the enricher. Currently the enricher writes the *file* before import, so DB sees enriched data as source data. New schema makes "enrichment survives import" first-class. |
| 8   | Port `enrich_campgrounds.py` → Kotlin `Enricher`                                                              | Step 7                                                                                                                                             | The hardest port (async, throttled, resumable). Worth doing only after the schema lets it succeed.                                                                          |
| 9   | Move `AvailabilityClient` mutex → app-level rec.gov rate limiter                                              | Step 8                                                                                                                                             | Once campsite poller and Kotlin enricher both hit rec.gov, the per-instance mutex stops protecting the global budget.                                                       |
| 10  | Tesla supercharger refresh in-process                                                                         | JVM Akamai TLS fingerprint bypass exists                                                                                                           | Open research problem; no good Kotlin library today. Not on the critical path for the single-stack goal because Tesla refresh is a different artifact (pricing cache, not POIs). |
| 11  | Cron / scheduled cadence                                                                                      | Manual triggering becomes a chore                                                                                                                  | Trivial once steps 1-9 are done — a `schedules` row per target reusing RFC 0001's `Scheduler`.                                                                              |
| 12  | Prod ingest worker container                                                                                  | Deploy box needs unattended refresh                                                                                                                | Not a maintenance pain today (ssh+make works). Comes after cron.                                                                                                            |


## Unresolved questions

1. **POST blocking semantics.** Default to a synchronous POST: `POST
   /api/admin/ingest/{target}` blocks until the run completes (or the
   per-phase timeout fires) and returns the final status payload. Simpler
   client (`curl -fsS …`), simpler Tilt button (exit code is run status),
   no polling loop. The async `202 + run_id + GET /runs/{id}` shape stays
   available for callers that want it (`POST .../{target}?async=1`); not
   the default. Per-target mutex still protects concurrent triggers.
2. **GH Action ZT auth.** If we ever trigger refreshes from CI, the GH
   Action needs a Cloudflare Service Token (ZT supports them) — flag in
   README when we wire it. Out of scope for v1.

## Decision log


| #   | Date       | Decision                                                                                                                          | Rationale                                                                                                                                                                                                                                   |
| --- | ---------- | --------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | 2026-06-06 | Wrap existing Python scripts in this RFC; sequence the port across ~8 follow-ups (see Future work).                              | Codex review surfaced 3 load-bearing flaws in the original port-everything plan. Each is its own RFC. Wrap-first establishes the seams (run records + target lock) so each later port is a contained swap. Single-language ingest remains the destination, not a "if it ever hurts" defer. |
| 2   | 2026-06-06 | New `ingest_runs` table; do NOT overload `import_runs`.                                                                           | Phase rows include shell-only fields (exit_code, stderr tail) and parent/child relationships that don't fit the per-`Importer.run` audit shape. `import_runs` stays untouched.                                                              |
| 3   | 2026-06-06 | "Targets," not "sources." Lock is per-target.                                                                                     | Multiple scripts share `campgrounds.geojson`; per-source lock is insufficient. Target = unit of refresh = one coherent on-disk artifact.                                                                                                    |
| 4   | 2026-06-06 | No application-level authn. Cloudflare Zero Trust path rule on `/api/admin/*` is the boundary.                                    | Workload is idempotent and non-sensitive (refresh trigger + run status). ZT already fronts the deploy. In-app token would be moving parts for no risk reduction.                                                                            |
| 5   | 2026-06-06 | Admin API only runs on hosts where `data/` is writable (dev machine, deploy host). Prod container's read-only mount is preserved. | Codex flagged: production mount is read-only by design. Ingest is host-side, not container-side.                                                                                                                                            |
| 6   | 2026-06-06 | Importer atomicity unchanged in **this** RFC, but flagged as Future-work step 6 (transactional `Importer.run`).                   | Prior justification ("transaction holds the pool too long") doesn't survive correctness > fetch-perf. ~10s of `PoiRoutes` blocking during a manual admin refresh is acceptable; partial-state-on-crash isn't.                                |
| 7   | 2026-06-06 | Sync POST is the default; async (`?async=1`) is the override.                                                                     | Sync makes `curl` and Tilt buttons "just work" — exit code = run status. Polling loop is extra moving parts. Per-target mutex still protects concurrency.                                                                                   |
| 8   | 2026-06-06 | No scheduler/cron in v1.                                                                                                          | Manual cadence works; mixing observability with automation widens scope. Cron rides along after most ports complete (Future work step 11).                                                                                                  |


## GSTACK REVIEW REPORT


| Review        | Trigger               | Why                             | Runs | Status             | Findings                                                              |
| ------------- | --------------------- | ------------------------------- | ---- | ------------------ | --------------------------------------------------------------------- |
| CEO Review    | `/plan-ceo-review`    | Scope & strategy                | 0    | —                  | —                                                                     |
| Codex Review  | `/codex review`       | Independent 2nd opinion         | 1    | issues_found       | 14 findings on prior draft; 3 blocking, all addressed via scope pivot |
| Eng Review    | `/plan-eng-review`    | Architecture & tests (required) | 1    | clean (post-pivot) | scope reduced, all blocking findings resolved                         |
| Design Review | `/plan-design-review` | UI/UX gaps                      | 0    | —                  | — (no UI in scope)                                                    |
| DX Review     | `/plan-devex-review`  | Developer experience gaps       | 0    | —                  | —                                                                     |


- **CODEX:** Surfaced 3 RFC-blocking issues on the prior draft (importer overwrites enrichment, read-only prod data mount, campgrounds is composite artifact not separable). All three resolved via scope pivot from "port to Kotlin" → "wrap-and-observe."
- **CROSS-MODEL:** Initial review and Codex aligned that the original port plan was overscoped; the pivoted RFC reflects that consensus.
- **UNRESOLVED:** 4 (see Unresolved questions).
- **VERDICT:** ENG CLEARED — ready to implement. CEO review optional (not a product change). Design review N/A (no UI).

