# Step 4 handoff — the TypeScript importer and its differential

Written at the end of the session that executed step 4. Its companion is
`2026-10-28-step-4-rulings.md`, which holds the reasoning behind the decisions;
this file holds the state and what is left.

Everything below was measured. Where something is an estimate or was not
verified, it says so.

## State at handoff

- Branch `claude/ts-importer-differential-e5da0d`, head `e5fb8f64`, on
  [PR #832](https://github.com/wwchen/roadtrip/pull/832) against `master`. Open,
  not merged.
- CI green on that head: 19 jobs, including `importer-differential`, which ran
  for the first time and passed.
- `make test` green locally: exit 0 in 7 m 35 s.
- The step-4 range is `6f4537ac..HEAD`. Note the branch also carries
  base-branch merges, so `apps/web` changes in that range come from #831 and
  `master`, not from this work.

## What was ported

The Kotlin ETL tree (`backend/src/main/kotlin/ca/floo/roadtrip/service/etl/`)
reimplemented in TypeScript as a CLI under `packages/worker`, writing catalog
rows through `@roadtrip/db`. Thirteen adapter families across eighteen import
targets. No UI: the importer has no browser surface, and the Vercel preview on
this PR exercises #831's two pages, not this work.

| area | source files | lines |
| --- | --- | --- |
| `vendors/aspira` | 23 | 1,600 |
| `vendors/recgov` | 3 | 766 |
| `vendors/campflare` | 6 | 629 |
| `vendors/reservecalifornia` | 12 | 427 |
| `vendors/tesla` | 2 | 311 |
| `vendors/reserveamerica` | 13 | 294 |
| `vendors/bcparks` | 3 | 281 |
| `vendors/osmpf` (Planet Fitness) | 1 | 143 |
| `etl/` framework | 20 | 1,705 |
| `repo/` + `cli/` + `registry/` | 11 | 1,241 |
| tests | 45 | 12,637 |

## What proves it

Both importers run over the same real vendor captures into two databases in one
PostGIS container. The Kotlin rows are copied by binary `COPY` into a
`reference` schema beside the TypeScript rows and compared with a
`FULL OUTER JOIN` over a natural key using `ROW(...) IS DISTINCT FROM ROW(...)`.

Full capture set, all three modes, `0 differences across every diffed table`:
17,198 campgrounds, 457,023 campsites, 24,229 pois, 5,465 superchargers,
1,566 Planet Fitness locations, 18 import_runs — identical on both sides, all
eighteen collision pins exact.

```
make importer-diff          # all three modes, stops on the first failure
node scripts/importer-differential.mjs --emit-seed <path>   # single pass + seed
node scripts/etl-oracle.mjs check                           # the oracle (needs a JVM)
```

The three modes and why all three exist are in the rulings document. The short
version: the single pass checks parse and transform; `--passes 2` reaches the
`ON CONFLICT` path an insert into an empty database cannot; `--prove-update-keys`
blanks every compared column between passes so a column missing from one side's
update set stays blank there.

## Open punchlist

Nothing here is known to make the importer wrong. These are unguarded
invariants, coverage limits and one design call. Each names where it lives and
what it costs.

### Done on 2026-11-03

1. ~~**Unbounded `docker exec` in `withPostgis`**~~. Every docker call in
   `scripts/schema-reference.mjs` is now bounded, and a test fails if any of the
   bounds is removed. The bounds:
   - 30 min for the data execs, `run` and Flyway, overridable with
     `ROADTRIP_DOCKER_TIMEOUT_MS`.
   - 2 min for `port`, `CREATE DATABASE` and teardown.
   - 3 min in total for the readiness wait, with each probe capped at 5 s.

   Measured on a full-set single pass on emulated arm64, which ran clean under
   these bounds:
   - The longest single call was 219.6 s: the 1.1 GB seed restore.
   - The longest control call was 0.5 s.
2. ~~**`COLLISION_PINS` correspondence is unchecked**~~. A unit test now
   requires the pinned names to be exactly the enabled registry targets.
3. ~~**`cacheRefusal` has no test**~~. It now has a table of refusal cases,
   and every branch was checked against a mutant.

### Coverage limits

4. **`NOT_IMPORTED_TABLES` is never checked at runtime** (A/L9,
   `scripts/importer-diff-columns.mjs`). Seventeen tables are named as written
   by neither importer and only their existence is verified. Asserting they are
   empty on both sides after a run is free today and would settle "a table
   written by neither side" rather than taking it on trust.
5. **`--prove-update-keys`' anti-vacuity check is per table** (A/L6), so a
   column that is always blank is never actually blanked and the mode proves
   nothing for it.
6. **`COVERAGE_EXEMPT_TABLES` is satisfied by prose alone** (A/L1) and is
   checked before `spec.joins === undefined`.
7. **The Kotlin import scan is one hop deep** (A/L5,
   `scripts/test_ci_image_builds.py`). It reads `service/etl` and `repo` and is
   `^import`-anchored, so it cannot see a transitive dependency or a
   fully-qualified reference with no import. `tools` and `config` are in the
   watched lists by hand for that reason.
8. **The vendor adapters had per-task reviews but no whole-diff pass.** A defect
   confined to one adapter and introduced after its own review would not have
   been caught by the final reviews, which covered the proof machinery and the
   shared seams. The full-set differential is what stands behind them.

### Faithfulness details

9. **`decodeLong` narrows a Kotlin `Long` to ±2^53−1** (B/4, PLAUSIBLE). The
   only call site is the Overpass `id`, unreachable at today's OSM ids.
10. **`isRecord` lacks the `isWideNumber` guard its sibling `isJsonObject` has**
    (B/5, `packages/worker/src/etl/envelope.ts`), so a header integer above 2^53
    decodes where Kotlin rejects the envelope. Unreachable today.
11. **`failureNotes` differs from Kotlin's fallback** (B/6): it falls back on an
    empty rather than null message and substitutes a JS constructor name, so
    `import_runs.notes` is not diffable for failures either way.
12. **`engines.node` is `>=22.12.0`** (B/7) while the `JSON.rawJSON` carrier was
    only ever verified on v22.23.4, with no feature detection. One command
    settles whether the floor is real.

### Design calls

13. **`FlushCounts` layering** (B/8). Defined in `repo/bulk.ts` and imported
    downward by `etl/batch.ts` and `etl/terminal-sinks.ts`, against AGENTS.md;
    the Kotlin has it as a framework type (`TerminalEtlBinding.kt`). Moving it
    naively inverts the dependency rather than removing it, so it needs a
    decision about where the seam belongs, not a quick edit.
14. **`make importer-diff` cannot exit 0 in its own cached-dump workflow**
    (A/L7): passing `--reference-dump` through `IMPORTER_DIFF_ARGS` runs modes 1
    and 2 to completion and then fails in seconds on mode 3, which refuses the
    flag by design. Documented in the Makefile; not fixed.
15. ~~**`containerName()` is dead**~~ (A/L8). It now has a caller: the test
    that bounds a buffered docker call.
16. **Two mild self-computing asserts** (B/9, `json-decode.test.ts`).

## Carried decisions — do not re-litigate without new evidence

These were decided deliberately; the rulings document has the full reasoning.

- **The oracle's `check` mode stays out of `make test` and CI.** It needs the
  Kotlin classes compiled *and* the worker suite run under a recorder, and it
  cannot run at all once the Kotlin importer is deleted. Its committed corpora
  now have a JVM-free floor guard in `make test`, so they cannot be emptied
  silently, but the comparison itself is run by hand.
- **`--passes 2` is not the only mode that reaches the conflict path.**
  `passPlan` forces `--prove-update-keys` to at least two passes as well.
  Whether it strictly subsumes `--passes 2` is open and unmeasured; no defect is
  known that one catches and the other misses, and none has been looked for.
  Both are kept rather than dropping a gate on an unproven redundancy.
- **`db/generated` is deliberately outside the watched trees and provenance:**
  jOOQ generates it from the migrations, which are watched.

## A measurement caveat that affects one of those decisions

The CI scoping decision — one mode in CI, three in `make importer-diff` — was
made against locally measured timings of 1,981 s, 3,352 s and 3,704 s, totalling
2 h 30 m against a 30-minute bar.

Those are **arm64 numbers under QEMU emulation**, because
`postgis/postgis:16-3.4` is a single-architecture amd64 tag with no arm64
variant. CI is native amd64. The single pass took **10.9 minutes** there, about
a third of the local figure, and `timeout-minutes: 150` was sized against the
inflated number.

Scaling the other two modes by the same ratio puts all three at roughly 50
minutes, which is a different decision than the one that was made. That is an
extrapolation from one data point and the modes do different work, so the honest
way to settle it is to time `--passes 2` and `--prove-update-keys` on CI. Until
someone does, treat the one-mode decision as resting on a number that does not
describe CI.

## Ephemeral artifacts

The session's full record was written to `.superpowers/sdd/2026-10-28-typescript-importer-differential/`
in the worktree that ran step 4, which is **git-ignored** — a `git clean -fdx`
erases it and leaves the code untouched. All 84 files were copied on 2026-11-03
to `~/code/wwchen/roadtrip-archive/sdd/2026-10-28-typescript-importer-differential/`,
outside the repository and every worktree, and that copy is the one to read:

| file | lines | holds |
| --- | --- | --- |
| `progress.md` | ~1,550 | the ledger: every ruling, correction and mutation proof |
| `final-review-A.md` | 473 | the proof machinery review (3 medium, 9 low) |
| `final-review-B.md` | 515 | the shared ETL seams review (3 medium, 5 low, 1 info) |
| `task-14-review.md` | — | Task 14's review (3 important, 5 medium, 5 minor) |
| `task-14-re-review.md` | — | the scoped re-review of the first fix round |

This document is the durable extract. The reviews hold more detail per finding
than is reproduced here — file:line, failure scenario, and a CONFIRMED or
PLAUSIBLE verdict on each — and live only in that archive copy.
