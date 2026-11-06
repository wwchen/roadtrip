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

### Done on 2026-11-03, second pass (PR #836)

4. ~~**`NOT_IMPORTED_TABLES` is never checked at runtime**~~. Each run
   fingerprints the seventeen tables on both databases after migration and
   refuses to diff if either importer changed one.
5. ~~**`--prove-update-keys`' anti-vacuity check is per table**~~. Still a
   coverage limit, but no longer tacit: the run names, per side, every column
   already blank in every row, which the mode cannot prove.
6. ~~**`COVERAGE_EXEMPT_TABLES` is satisfied by prose alone**~~. Each exemption
   names its stand-in check, the completeness test requires that check to hold,
   and a table with a joins list cannot be exempt.
7. ~~**The Kotlin import scan is one hop deep**~~. It is now a transitive,
   file-level closure from `tools`, `service/etl` and `repo`, following imports,
   fully-qualified references and same-package use. It found a real two-hop path
   the review had ruled out (`ConfigSection` → `AppConfig` → `BookingConfig` →
   `RecGovKeepaliveJob.kt`); those five files are now watched and in provenance.
8. ~~**No whole-diff pass over the vendor adapters**~~. Three independent
   reviews covered all thirteen families. Two real divergences were found and
   fixed: typed decoders rejected quoted numbers and booleans that kotlinx
   1.11's tree decoder accepts (a quoted Tesla latitude failed the whole run;
   a quoted stall count silently stripped a row's enrichment), and two
   extended source payloads lost the vendor's key order. Neither is reachable
   from today's captures, which is why the differential was green.
9. ~~**`decodeLong` narrows to ±2^53−1**~~. Kept as a refusal, now pinned by a
   test naming the Kotlin `Long` values it refuses.
10. ~~**`isRecord` lacks the carrier guard**~~. Fixed; `isJsonObject` delegates
    to it, so there is one predicate.
11. ~~**`failureNotes` differs from Kotlin's fallback**~~. Left as is, because a
    JVM simple name cannot be reproduced, so `import_runs.notes` for a failure is
    undiffable either way. The row-error log now renders lists as SLF4J does.
12. ~~**`engines.node` floor unverified**~~. Real: node 22.11 through 22.21 all
    have `JSON.rawJSON`, `JSON.isRawJSON` and the reviver's `source`.
13. ~~**`FlushCounts` layering**~~. Moved to `model/`, the layer both `etl` and
    `repo` already depend on, so neither imports the other for it.
14. ~~**`make importer-diff` cannot exit 0 with a cached dump**~~.
    `IMPORTER_DIFF_CACHE` gives modes 1 and 2 a dump each and mode 3 none.
16. ~~**Two mild self-computing asserts**~~. The double assertion uses the
    literal; `collapseAsciiWhitespace` has direct tests, U+000B included.

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

## The CI scoping decision, re-measured

The decision to run one mode in CI and three in `make importer-diff` was made
against timings of 1,981 s, 3,352 s and 3,704 s. Those were arm64 numbers under
QEMU emulation, because `postgis/postgis:16-3.4` has no arm64 variant.

Measured on 2026-11-03 on `ubuntu-latest` (run 41688377263), job wall time
including setup: single pass 10 m 12 s, `--passes 2` 11 m 01 s,
`--prove-update-keys` 16 m 33 s, all `0 differences`. Run as a matrix, the
gate costs 16.5 minutes, inside the original 30-minute rule, so CI now runs
all three modes (PR #836) with `timeout-minutes: 45`.

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
