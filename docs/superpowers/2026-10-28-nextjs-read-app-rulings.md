# Next.js read app — decisions I made on your behalf

The calls taken while executing
[2026-10-28-nextjs-thin-read-app.md](plans/2026-10-28-nextjs-thin-read-app.md), recorded here
because the execution workspace that held them is throwaway scratch.

Each is a call the plan did not settle and that I took without stopping to ask, with what it
costs if it was wrong. A few were settled by you and are marked as such. Several correct errors of
mine, in the plan or in a brief, rather than deciding something new; those say so. The recurring
pattern is the one step 2 named: a test or a gate that could not fail. It recurs in every task,
including inside the fix for it (Task 2's BOM test).

Companion documents: [web-app.md](../web-app.md) for the deployment as built, and the C4 section
of [the spec](specs/2026-10-27-kotlin-to-typescript-backend-design.md) for the datastore evidence.


## Decided before execution

- `apps/web` as the directory name. Settled by you (Q5). Cost if wrong: a rename.
- Read-only by `default_transaction_read_only=on` on the connection, not a new production role.
  Settled by you (Q4), with the caveat written into the plan that it guards accidental writes and
  is not a security boundary. **Superseded in Task 5**: the parameter turned out to be a no-op on
  Neon. See below.


## Pre-flight scan

- **P1: Task 1's failure proof needed two commits.** The plan expected
  `check-worktree-sparse-paths.mjs` to fail on an unregistered `apps` directory, but that script
  reads `git ls-tree -d -r --name-only HEAD`. Uncommitted and even staged files are invisible to
  it, so the guard would have passed and the proof could not happen as written. Task 1 became two
  commits: the workspace unregistered (`a2fe00c8`, guard observed failing), then registered
  (`cb11ae29`, 21 entries, guard passing). Dropping the proof was rejected, since that is how this
  project has shipped three gates that could not fail. Cost if wrong: one extra commit.
- **P2: `DEFAULT_SEARCH_BBOX` lives in `apps/web/src/server/boundary.ts`**, beside
  `bboxToPolygonGeoJson`, not in `config.ts`. It is a presentation default of the route layer and
  nothing about it differs per environment. The plan named it and gave it no owning file. Cost if
  wrong: one constant moves one file.
- **P3: the `.d.mts` resolution mismatch was left to the implementer to resolve concretely.** The
  tests import `withPostgis` from the root `scripts/schema-reference.mjs`, typed by a sibling
  `.d.mts`; `packages/db` resolves that under `nodenext` and `apps/web` uses `bundler`. Not a type
  assertion and not `@ts-expect-error`. Outcome: no shim was needed, `bundler` resolution does
  find the sibling `.d.mts` (verified with `--traceResolution`). Cost if wrong: the test files
  type-check under a config that differs from the app's, which is already true of `packages/db`.
- **P4: the root `npm run build` stays as Vercel's build command**, though it also builds
  `frontend`'s Vite bundle, which Vercel never serves. It is the exact command `make test` runs,
  so it cannot drift from what CI proves. Measured in Task 5: the root build is 33 s of a 162 s
  deployment. Cost if wrong: tens of seconds per Vercel build.
- **P5: the provisional probe boundary** (choose the representative boundary one task later, once
  real row counts exist). Dissolved by the scope cut, which removed the latency harness it served.
- **P6: found while preparing Task 3's dispatch.** `CampgroundSearchRepoTest.kt` has no
  self-intersecting-polygon test, and GEOS raises `TopologyException` mainly from overlay
  operations, which `ST_Within` and `&&` are not. The bowtie test the plan wanted might assert a
  path this query cannot reach. Ruling: the implementer determines it empirically, and deletes
  the test if it cannot fail rather than reshaping the query to manufacture a fault. Result: a
  bowtie does not raise from this query on PostGIS 3.4.3, no test was written, the handler was
  kept (it mirrors the Kotlin and costs nothing). Cost if wrong: the Review Focus line reads
  "handled, unreachable from this query" instead of "tested".
- Verified, not a finding: `@lew-ds/*` resolve from `registry.npmjs.org` and the repo has no
  `.npmrc`, so Vercel's root `npm ci` needs no registry auth. This was the one pre-flight risk that
  could have made the deployment unreachable.


## Task 1

- **react and react-dom pinned to 19.3.0, not 19.3.1.** The exact 19.3.1 pin deduped
  `frontend` (`^19.3.0`) up to 19.3.1. `frontend` is a production deployable and this task is
  additive, so a patch bump to what its bundle ships is out of scope however benign. Next 16.4.3's
  peer range is `^19.0.0`. Cost if wrong: the new app sits one patch behind until both are bumped
  together, deliberately.
- **`vitest run --passWithNoTests` replaced with one real test**, asserting that
  `next.config.ts`'s `outputFileTracingRoot` resolves to the repo root. The flag makes a
  zero-test suite green, which is the recurring defect. The test now pins a Vercel concern (see
  the scope cut). Cost if wrong: one small test to delete.
- **Left the stale commit message in `cb11ae29`.** It misdescribes build order and its
  `--passWithNoTests` paragraph went stale in the next commit. Fixing prose in a non-tip commit
  means a rebase that rewrites the commit carrying the guard-failure evidence, the one artifact
  worth protecting. Cost if wrong: one commit message misdescribes build order.
- **I closed three "cannot verify" items from the review myself:** installed `actionlint` (the
  settings already allow it) and linted `ci.yml` clean; ran the real `ci-passed` jq expression
  against synthetic `needs` JSON to show a failing `web-tests` selects itself and a passing one
  selects nothing; re-ran `make test` on the final tree, since it had last run before the fix
  round.
- **My error: I ran `make test` concurrently with the reviewer's npm runs and a Gradle build.**
  It failed in `frontend`'s vitest with "Failed to start forks worker" across ~14 files. Not a
  defect: the frontend suite alone passes, and `git diff` on `frontend/` was empty. `make test`
  boots Docker and Gradle and must not run beside a dispatched subagent. Every later run was
  serial.
- **Finding for you, not this branch:** `package-lock.json` on master is out of sync with
  `packages/db/package.json` (`@types/node ^26.6.5` is declared and the lockfile entry omits it).
  Benign, and every install silently corrects it. A one-line fix on master.


## Task 2

- **My brief's guard spec had two holes, caught by the implementer.** I specified a
  relative-import walk only. Task 1's tsconfig defines `@/* -> src/*`, so `@/server/...` from a
  client component walked straight past it; and `.js` specifiers resolving to `.ts`/`.tsx`, which
  is how this repo writes TypeScript ESM imports, would have missed the same way. Both probed
  against the real tree. Cost if wrong: the server-only guard would have passed code it exists to
  stop.
- **Three parsing holes in the shipped SPA guard `check-package-boundaries.mjs`, fixed in a
  shared `scripts/module-specifiers.mjs` for both guards at once, deliberately wider than Task
  2's scope.** The review found two walk-arounds in the new guard (`import(/* webpackChunkName */
  '@roadtrip/db')`, and a backtick specifier), inherited from a regex my own brief told the
  implementer to reuse. I checked the shipped guard and it had the same two: it returned zero
  violations on a fixture containing both. A third, found by the implementer and verified by me:
  without excluding `\r\n` from the specifier class, a stray `"` in a comment pairs with a quote
  on a later line, and on `// loaded via import "` followed by `import { schema } from
  '@roadtrip/db';` the old regex captures one bogus specifier and **does not capture
  `@roadtrip/db` at all**. That one is the worst of the three: the other two let a deliberate
  bypass through, this one hides an accidental violation, needing only a stray double quote in any
  comment above the import. Reasons for the wider scope: the plan's scope fence protects
  deployables and a CI guard is tooling; the change can only newly fail on a real violation; the
  two regexes were copy-pasted and fixing one guarantees drift. Commit `5a87e762`. Cost if wrong: a
  four-file tooling change on this branch instead of its own, and the exact violation counts in
  `check-package-boundaries.test.mjs` moved (8 to 12, 3 to 4, each rise attributable to an added
  fixture; the pre-existing fixtures still return 8 and 3).
- **The fix introduced a ReDoS; removed in `05bf9405`.** `\/\*[\s\S]*?\*\/` inside a repeated
  group backtracks exponentially on adjacent block comments with no following quote. Measured by
  me: 23 ms at 18 comments, 424 ms at 26, roughly 4x per two added, and the implementer measured
  155 s at 30. A false-negative fix had shipped a denial of service into a required gate. Replaced
  with an unrolled pattern: 5000 adjacent comments in 0.45 ms, 36 probe inputs identical, 186,150
  comment bodies fuzzed against the lazy form with no difference. Cost if wrong: a hung CI job on
  one pathological comment.
- **The BOM handling the fix added was dead code and its test could not fail.** U+FEFF is
  ECMAScript whitespace, so `'﻿'.trimStart()` returns the empty string and the constant and
  its `.replace` never did anything. Deleted both, rather than keeping either. The same defect
  step 2 named, inside the fix for it.
- **Interpolated template suffixes closed** (`` import(`@roadtrip/db${x}`) ``): same family, one
  line, and cheaper than leaving a known gap in a guard already being edited. `@roadtrip/db-tools`
  stays allowed.
- **Deleted the perf test's 100 ms budget assertion** (`5a367a15`). It added no coverage, since
  the hard timeout already catches the exponential case (64 comments never finish under the lazy
  pattern). It was the test's only spurious-failure surface: median scan 0.45 ms but the worst of
  60 unloaded samples was 18 to 21 ms, about 5x headroom rather than the 200x the number implies,
  in a suite that runs beside Docker and Gradle. Removed while it was not yet flaky, rather than
  after it cost someone an on-call hour. Kept `found === 0` and a 10 s hard timeout. This also
  retired the question of Node 22 versus 26. Cost if wrong: a polynomial regression is caught by
  the timeout later instead of by the assertion sooner.
- **Parked, strictness kept:** `import type` from `@roadtrip/db` in a client component is flagged
  though types are erased. Deliberate: `@roadtrip/domain` is the browser-safe package and the
  error message says so. The right seam, not just the right bytes.
- **Parked, no action:** the closure does not follow edges into other workspace packages
  (`@roadtrip/domain` has zero runtime deps by design); mis-cased imports unresolved on macOS
  (Linux CI and `tsc` reject them); a quadratic scan on an unterminated `/*`; `$import` and prose
  "from"/"import", unchanged from the original guard and over-matching in the safe direction for
  a deny-guard.
- **Parked, one word:** the blind-spot sentence says interpolation-leading template specifiers
  "cannot be seen" when the regex matches them and `isPackageSpecifier` never recognises them;
  "cannot be resolved" is accurate. A fresh implementer on a more capable model for one word is
  disproportionate. Cost if wrong: a guard message overstates its blindness, in the safe
  direction.
- **`.next` added to the skip list of the server-only guard only**, and
  `scripts/module-specifiers.mjs` added to both CI paths-filters. Without the second, a change to
  the shared module triggers neither job, the "guard watched by nothing" pattern.
- **Held the gate while an orphaned process ran.** A leftover test run pegged a core at 99% for
  eight minutes, almost certainly the implementer's proof that the perf test goes red. I did not
  kill it myself after the implementer's own `pkill` was denied; you approved the single PID. Load
  fell from 7.7 to 5.7. A pegged core had already produced one false `make test` failure.


## The scope cut (`9939360a`)

On your direction: production stays on mini-ca running the Kotlin stack, the parallel app goes on
Vercel only, no `pg_dump` of or copy of production, no latency harness. The plan went from 664
lines to 342. I audited Tasks 1 to 4 for anything the cut left purposeless:

- **`output: 'standalone'` removed** (`cea188c2`). It existed only for the deleted Docker image
  and Vercel does not want it.
- **`outputFileTracingRoot` and its test stay**, re-justified: Vercel needs it to resolve a
  monorepo root.
- **`/api/probe/campground-search` renamed `/api/campgrounds/search`.** It earns its place as a
  read endpoint, and its `Server-Timing` header is how the response-time question is answered at
  all.
- **`/api/health` kept.** Ten lines, and the fastest way to tell whether a deployment reaches its
  database.
- **`default_transaction_read_only` kept** because it enforces the app's own contract whatever it
  faces. This was the call Task 5 overturned.
- **Task 2 untouched.** `NEXT_PUBLIC_` matters more on Vercel, where env vars are set in a
  dashboard.
- **Task 5's step 1 became a probe, with the ruling that it must not edit the migration.** The
  migration is the artifact proven byte-identical to Flyway's. That ruling was overtaken by
  evidence; see Task 5.


## Task 3

- **A subset projection, not Kotlin's 40 columns.** A thin detail page needs identity and
  descriptive fields plus the JSONB ones that exercise the codec (`location`, `amenities`,
  `contact`, `price`). Reading through `@roadtrip/db` is the point; reproducing the projection is
  not. Cost if wrong: a later task widens it, which is additive.
- **Each JSONB field decodes independently, with `decodeErrors`.** The JSONB columns carry no
  `.$type<>` (step 2), so `decodeCatalogJson` is the only correct read. But the codec is strict
  and production rows predate it. A field whose decode throws `CatalogJsonError` becomes `null`
  and its name is appended to `decodeErrors` on the DTO. This is the difference between "the
  campground page is down" and "one field on the campground page is missing", and drift stays
  visible. Any other error still propagates. Cost if wrong: one extra DTO field.
- **The filter predicates (`siteType`, `groupSize`, `amenities`) are dropped from the search
  port.** The query keeps a `true AS passes` column so the Kotlin's shape survives, and the
  unfiltered shape is the one this slice needs. Cost if wrong: a later task adds them, additively.
- **Ids are `string` at the repo boundary.** `packages/db` declares ids `bigserial` in `bigint`
  mode and `JSON.stringify` throws on `BigInt`. Repos convert at their own edge, and routes
  convert once the other way (`parsePoiId` to `bigint`).
- **`postgres.js` returns int8 as a string from raw `db.execute`, not a `BigInt`. That made my
  Review Focus item 1 half-false.** The hazard is real for the query builder and absent from the
  raw-SQL reads this task writes. The plan's `String(row.id)` was a no-op and its mutation test
  could not fail. The implementer's fix routed driver string to `mapFromDriverValue` to `BigInt`
  to `String()`, machinery whose only purpose was to give a test something to detect, the same
  error as a vacuous test, inverted. I had it undone: `ids.ts` is a plain `string | bigint |
  number` to `string` converter that throws `UnsupportedIdError` on anything else, unit-tested
  per branch. Brief step 6's first demonstration was unperformable as written, my error from the
  same premise; the DTO serialization assertions stay as documentation and would catch a switch
  to the query builder. Cost if wrong: one function call instead of two.
- **The Kotlin's `geometryParseFaultMessages` is missing `{}`, and the TS port deliberately
  diverges from it.** PostGIS 3.4 emits two spellings of one error: `{}` gives `invalid GeoJSON
  representation`, `{"type":"Banana",...}` gives `invalid GeoJson representation`.
  `TopologyFault.kt:21` lists only the second, so `{}` escapes `isGeometryParseFault` and is a
  500 in the Kotlin, though that file's comment says it was confirmed against 3.4. Found by
  porting it; out of scope to fix; reported. The port matches the phrases case-insensitively,
  covering both with no false-positive risk, and a `JSON.parse` pre-check handles `not geojson`,
  which the Kotlin never meets because its service re-encodes a typed DTO. The divergence is
  deliberate and the reasoning is in the commit message. Cost if wrong: the port classifies one
  input the source does not.
- **`SEARCH_RESULT_LIMIT` defaults to 200 with a named ceiling of 1000 enforced by
  `ConfigError`.** The plan's 5000 plus full summaries for every id is a very large response for a
  thin app over a pooled connection. Cost if wrong: a config value.
- **`createDb`'s new `connection` option got a test in `packages/db`'s own suite** (asserting
  `SHOW default_transaction_read_only` is `on` with the option and `off` without), not only in
  its downstream consumer. A shared package's option covered only downstream is how drift starts.
  `connection: {}` was confirmed equivalent to omitting the key and simplified to
  `connection: opts.connection`. Cost if wrong: one redundant test.
- **Four tests that could not fail, and I fixed the fixture, not the code.** The reviewer's
  extraction of both SQL strings (whitespace normalised, binds to `?`, the Kotlin's `$passes` to
  `true`) showed them **byte-identical at 751 bytes**, so the code was already correct. The two
  Important findings were that the fixture was too uniform to discriminate: it soft-deleted a POI
  but never a campground, and `NEAR_POI_ID=1001 < FAR_POI_ID=1002` with a `poi_id ASC` tiebreak,
  so deleting `dist ASC` stayed green. Applying all four mutations at once left 21 of 21 green.
  Two Minors had the same cause (`p.poi_type` unguarded because every POI was a campground;
  `ST_PointOnSurface` versus `ST_Centroid` unguarded because every geometry was a point, where
  they coincide). Fixed in `246422fc` with a soft-deleted campground, swapped ids, a non-campground
  POI and a concave C-shaped polygon. The four-mutation run is now 7 failed and 17 passed, each
  mutation attributed to a named test, and the re-reviewer reproduced it independently. The
  concave-polygon test asserts `ST_Within(point, geom)` in SQL and pins no coordinates, so it is
  robust across PostGIS patch versions. Bonus: removing `ST_Within` alone now fails three tests,
  because the C-shape's bounding box overlaps the boundary while its centroid falls outside.
  Cost if wrong: a fixture that is wider than it needs to be.
- **My error: `POI_TYPES[0]` as the campground discriminator is positional.** Reordering the
  tuple in `packages/db/src/schema/catalog.ts` would make the search filter on the wrong type and
  return nothing, while the CHECK constraint, being a set, would not complain. Fixed in Task 4's
  dispatch by exporting a named `CAMPGROUND_POI_TYPE` and deriving the tuple from it
  (`3ebdc64e`), rather than spending a third fix round on two lines.
- **`findSummariesByPoiIds` drops non-canonical ids** (`'01001'` returns nothing, proven), where
  the Kotlin's `Long` keys matched. Handled as a plan amendment, not a repo fix: `parsePoiId`
  canonicalises, so every id reaching a repo must go through it. Committed as a stated Task 4 test
  (`8c75160f`). Cost if wrong: a repo called with a raw string silently misses.
- **`findDetailByPoi` gets a deterministic `ORDER BY cg.id`** and keeps taking the first row.
  jOOQ's `fetchOne` throws on more than one; the port would take an arbitrary one, and
  `poi_campgrounds_campground_uidx` is unique on `campground_id`, not `poi_id`. Deterministic
  beats arbitrary, and rendering one of two linked campgrounds beats a 500 on a read page. This is
  a divergence from the source.
- **Parked: the `p.geom && boundary.poly` prefilter is untestable by any fixture.** It is provably
  redundant for correctness (a centroid inside the boundary is inside the geometry's bounding box,
  so the boxes intersect). It exists to let PostGIS use the GiST index, so it changes the plan, not
  the result set; only an `EXPLAIN` assertion could guard it, which is disproportionate here.
  Cost if wrong: a future edit drops it and loses index usage silently, costing performance, not
  correctness.
- **Parked: `ORDER BY cg.id` is untestable.** The planner controls the row order the fixture
  would see, so any assertion would pass with or without the clause; a test whose outcome depends
  on planner behaviour misleads. Kept and recorded as uncovered. Cost if wrong: nothing, the
  clause only makes the result more stable.
- **Parked: the three `not.toContain` tests would pass vacuously** if their row were excluded for
  some other reason. Each is individually flipped by removing its own predicate in the mutation
  run, which is the correct guard and the discipline to repeat on any future fixture.
- **Accepted without change:** `fileParallelism: false`, the `server-only` vitest alias, three
  extra test files, and a mid-round restart of Docker Desktop by the implementer after the daemon
  died (it touched nothing belonging to others).


## Task 4

- **Task 4's implementer was killed by a weekly API rate limit and resumed by messaging the same
  agent.** Its committed and uncommitted state was checked coherent first; a leftover
  `catalog.ts` edit exporting the latitude/longitude bounds was exactly what the brief asked for,
  so `boundary.ts` validates against the schema's own named bounds. Cost if wrong: none.
- **A `healthRepo`/`healthService` pair beyond the plan's literal text.** The plan said
  `/api/health` runs `SELECT 1` through `serverDb()`; routes must not touch the database
  directly, so this is the correct reading of `AGENTS.md`.
- **The TDD gap is closed with a mutation check, not a re-enactment.** The implementer wrote
  implementation alongside tests and never watched them fail. Re-enacting red then green after the
  fact would be theatre. What step 2 protects is a test that cannot fail, so the round mutated
  across the 41 new tests: 13 of 16 went red, and the 3 that did not were the findings.
- **My brief was wrong about `force-dynamic`: it is not load-bearing in Next 16.4.3.** I wrote
  that without it the build would open a database connection and every Vercel build would fail.
  The implementer removed it from all four routes and `env -u DATABASE_URL npm run build` still
  exited 0 with all four routes reported dynamic: GET handlers are uncached by default and both
  pages read `params`/`searchParams`. Step 6's passing build never evidenced the directive at all.
  Plan corrected (`349a72eb`). The directive stays, as cheap insurance against a later edit that
  makes a route prerenderable, guarded by a per-route `dynamic === 'force-dynamic'` assertion,
  the only thing that can tell its presence from its absence. Cost if wrong: four lines and four
  assertions that guard nothing today.
- **`safeLink` tested first.** Unplanned code in the detail page: an `http:`/`https:` allowlist
  stopping a `javascript:` URL reaching an `href`, on `reservation_url`, which is vendor-supplied
  data arriving through the importer. Correct, with zero coverage, which made it the most
  consequential untested thing in the task. Extracted to an importable module and tested against
  `javascript:`, `data:`, `vbscript:`, `file:`, `blob:`, case and whitespace variants, malformed and
  relative strings; `//example.com/x` returns `null`. The page test is the sole guard on the call
  site, because `safeLink.test.ts` cannot see the wiring. Cost if wrong: a small module moves and
  gains tests.
- **`notFound()` paths tested** by calling the page modules directly with the service stubbed by
  one `vi.mock`. Nothing imported them, so the detail page's 404s were proven only by curl.
- **`apps/web/AGENTS.md` gitignored** (`a5f1de11`). `next dev` generates one. `AGENTS.md` is this
  project's architecture-rules file, loaded automatically by agents, and a generated copy would
  shadow the real one for anything working inside that directory. Cost if wrong: one ignore line.
- **Accepted:** a bad-bbox page returns 200 with a rendered alert, since an App Router page cannot
  set a status code (the route returns 400); `ConfigError` and database failures propagate to a
  500 on the pages and search API, with only `/api/health` folding them into 503, and the
  Review Focus concern was user-input-driven 500s, of which none could be produced across 13
  degenerate and 147 random in-range boxes; `InvalidBoundaryError` re-exported from the service so
  routes never import a repo; `Server-Timing: db` covers client construction, both queries and DTO
  decoding, so it overstates pure query time, which is the honest direction to err in.
- **Two review Minors carried into Task 5 rather than fixed:** postgres.js has no `max`,
  `connect_timeout` or statement timeout, so the health race bounds the response and not the
  resource (answered in Task 5: see `web-app.md`); and `/api/health` is public and queries on every
  hit, so a crawler holds Neon's autosuspending compute awake. Committed to the plan (`a0ea0156`).
- **The `/search` and health-timeout coverage went back for one more round** (`8f233fae`): three
  mutations left the app tests 44 of 44 green. The same round pulled `BBOX_PARAMETER` and the
  parse-then-service-then-map logic out of the page and route into one shared `bboxSearch.ts`
  (`AGENTS.md`'s own "prefer the shared seam"), and handled the antimeridian as a named problem.
  The re-reviewer ran the old page and route from `ec3d122f` beside the new ones across 16 input
  classes and 2 service modes and found identical output, except for the one intended change:
  `170,0,-170,10` is now rejected on both surfaces. Cost if wrong: the shared helper takes
  `string | undefined`, so the request-to-string policy is still duplicated at the two edges.
- **Parked: a repeated `?bbox=a&bbox=b` still diverges between page and route.** The route's
  `searchParams.get` takes the first value and returns 200; the page treats an array as an empty
  bbox and shows the alert. Pre-existing, not caused by the refactor, and the class of bug the
  shared helper exists to prevent; the clean fix is to widen the helper to `string | string[] |
  undefined` and normalise once. Another implementer round on a more capable model is
  disproportionate for a malformed request both surfaces answer sensibly. Deferred to the
  whole-branch review's fix wave, alongside Task 2's wording minor. Cost if wrong: one obscure
  input class answers differently on two surfaces.
- **Parked, unmeasured:** the refactor changed user-facing error copy (the page alert lost its
  `<code>` example); moving `started` ahead of `parseBbox` leaves the suite green, so the
  `dbMillis` bracket holds by construction rather than by test, a difference of microseconds; the
  `rel="noopener noreferrer"` on the Reserve link is unasserted, and there is no `target="_blank"`
  so the concern does not arise; `BBOX_PROBLEM_MESSAGES` is presentation copy under `server/`.
- **Meta-lesson: a mutation that silently fails to apply looks exactly like a test that cannot
  fail.** A reviewer's spot-check did not apply because of a macOS `sed` quoting error and the
  suite stayed green; it noticed, redid the mutation with a script, and checked the diff before
  running. Three agents hit macOS `sed` quoting. Diff the mutation before trusting a green result.


## Task 5

- **I ran the step 1 probe myself.** Its answer shapes the whole task. Neon project
  `square-sky-33743589`, Postgres 16, `aws-us-east-2`. `npm run migrate --workspace
  @roadtrip/db` against the direct endpoint fails with SQLSTATE 42501 on `GRANT pg_read_all_stats
  TO grafana_reader` in `0001_raw_objects.sql`. `roadtrip` is a member of `pg_read_all_stats` but
  not `WITH ADMIN OPTION`, and `neon_superuser` lacks it too, so no role can perform the grant and
  `SET ROLE` does not help. The failure is total: Drizzle's migrator rolls the set back, so
  `public` held only PostGIS's own three objects.
- **It is one statement out of 18, and the app needs nothing from it.** I executed the other
  `grafana_reader` privilege statements against Neon in one block and all succeeded: every one is
  an object privilege, which needs schema ownership. The grant is the file's only role-membership
  statement. What the app needs from that file is `campground_site_summary`; the migrator is
  all-or-nothing, which is why it could not simply be skipped.
- **The ruling not to edit the migration was overtaken by evidence, and I took the fork to you
  rather than improvising through it.** The change is a capability gate
  (`pg_has_role(current_user, 'pg_read_all_stats', 'MEMBER WITH ADMIN OPTION')`, notice
  otherwise), `d91dd419`. It is invisible to the equivalence gate: `dump()` runs `pg_dump
  --schema-only --no-owner --no-privileges`, and a role membership is cluster-level and not
  emitted by a single-database dump. I checked that rather than assumed it, and the gate ran
  15 of 15 afterward. Cost if wrong: Grafana's query-stats role loses a privilege on hosts where
  the migrating role cannot grant it; mini-ca, where Grafana runs, still gets it.
- **Probe residue left in place:** the `grafana_reader` role from the probe stays on Neon. Neon
  tool guidance is not to run destructive SQL unasked, the `DO` block creating it is idempotent,
  and it changes nothing about the blocker.
- **The import goes in two local hops, with no production tooling touched.** Local Kotlin import,
  then `pg_dump`/`psql` of the four catalog tables into Neon. Counts and content md5s match: 11,281
  pois, 11,281 campgrounds, 11,281 `poi_campgrounds`, 299,857 campsites; 596 MB of the 1 GiB limit.
  The Kotlin backend is never pointed at Neon, because its Flyway carries the same grant.
- **`prepare: true`** on the pooled endpoint, proven rather than assumed: 1164 of 1164 queries
  succeeded with `true` and with `false`, wall time within 3%. `connect_timeout=10` and
  `idle_timeout=20` in the URL; `max` stays at postgres.js's 10 because it is not settable from the
  URL (`?max=5` yields `Array('5')`, one slot). Cost if wrong: ten connections per instance behind
  the pooler.
- **The read-only guard did nothing on Neon. I verified it myself rather than relaying it.**
  Through the app's own `createDb` with `connection: { default_transaction_read_only: 'on' }`
  against the pooled endpoint, `SHOW default_transaction_read_only` returned `off` and `CREATE
  TABLE` succeeded as the owner role (dropped at once). Neon ignores startup parameters. The
  guarantee I wrote into the plan's Decided section was false where the app runs, and Task 3's
  read-only test passed locally and said nothing about Neon. Not exploitable, since the review had
  found parameterisation clean; a defence-in-depth loss.
- **Replaced by a SELECT-only role with role-level settings, and the result is stronger than the
  claim it replaced** (`b92d252e`). `roadtrip_web` is `NOSUPERUSER`, has `ALTER ROLE ... SET
  default_transaction_read_only = on` (which Neon honours, and which survives the pooler), `USAGE`
  on the schema, and `SELECT` on the four tables the app reads. I confirmed it myself: `CREATE
  TABLE` now fails with `cannot execute CREATE TABLE in a read-only transaction`. With the
  transaction forced `BEGIN READ WRITE`, the grants still stop it (`permission denied for table
  pois`), and `SELECT` on `api_cache` is denied. There are two independent layers, and the old
  "not a security boundary" caveat was premised on a session being able to flip
  `transaction_read_only` off; with SELECT-only grants underneath, flipping it buys nothing. The
  owner role `roadtrip` and its credentials still exist, which is why `web-app.md` keeps the
  caveat. The `connection` option stays in `openServerDb` because it works on ordinary Postgres.
  Cost if wrong: the app holds a second Neon role to rotate.
- **The bbox area cap is 36 square degrees, chosen from a planner flip.** An unbounded bbox on a
  public endpoint cost 9.9 s at 0.25 CU for a continental box, reproduced on local PostGIS 3.4.3,
  so it is a plan problem and not Neon's: the planner estimates 1 row for the spatial filter and
  nest-loops a seq scan of `poi_campgrounds`, filtering 121 million rows. I suggested picking from
  three data points; the implementer ran an `EXPLAIN ANALYZE` ladder instead and found a flip
  between 49 and 60 square degrees: 9 to 49 ran 9 to 16 ms over the densest area, then 60 gave
  660 ms, 64 gave 795 ms, 100 gave 1.0 s, 400 gave 2.6 s. A 16x4 box of 64 stayed fast, so area is
  a proxy for the flip, not its cause. 36 sits 27% under the largest box fast in every sample and
  40% under the smallest slow one. It did not take my PNW-end hint: that box is 63 square degrees
  and already on the slow side. Verified live by me: the whole-world box returns 400 in 0.25 s
  where it took 9.9 s; an in-limit Tahoe box returns 200 with 78 campgrounds. Deleting the check
  reddens 9 named tests (`672117d9`). **The cap sits just under a statistics-dependent flip; it
  must not be raised without re-measuring.** Cost if wrong: a legitimate wider viewport is refused
  with a 400 and "zoom in".
- **Settled by you: the production alias stays public.** The first deployment of a new Vercel
  project is assigned the production target, so `roadtrip-web-nu.vercel.app` answers with no
  login; deployment, branch and team-slug URLs are protected. The implementer changed no
  protection setting, which was right, and you chose to leave it public and to cap the bbox and fix
  the role. Cost if wrong: anyone can spend compute on the capped endpoints.
- **Parked, offered rather than done (scope was exactly "cap the bbox and fix the read-only
  role"):** a role-level `statement_timeout` on `roadtrip_web`, one `ALTER ROLE`, no code, which
  would bound any other slow plan the area cap does not; and `/api/health` still querying on every
  hit, now the last path where a visitor costs compute. The implementer's own design (public
  DB-free liveness, database check behind a secret) is the right shape. Cost if wrong: a crawler
  holds the compute awake and bills for it.
- **Noted:** the `Server-Timing` db figure ranged from 82 ms warm to 385 to 413 ms after a compute
  resume; Neon reports `pooler_enabled false` while the `-pooler` host works. Build timings: clone
  41 s, `npm ci` 74 s, root build 33 s, ready at 162 s.


## Which of the plan's hazards proved real

The project's habit is that the live system wins over the document.

**Real:**

- **The `pg_read_all_stats` grant.** The plan flagged it and it blocked the whole schema. It is
  evidence against C4; see the spec.
- **The `data/raw` build cost.** Measured at 133 MB packed and 1.9 GB checked out in 10.9 s
  locally, and 41 s for the clone on Vercel inside a 162 s deployment. Found not to matter, so
  nothing is being done about it. A real cost, a negligible one.
- **JSONB codec strictness.** The reason for per-field decoding and `decodeErrors`. It shaped
  the design and the malformed-row test exercises it; the ledger records no live row that failed
  to decode.
- **The `bigint` serialization hazard**, in the half I did not get wrong: real for the query
  builder, absent from the raw-SQL reads this app writes.

**Not real:**

- **PostGIS 3.3 versus 3.4 message differences.** Neon runs 3.3.10 and its fault messages are
  identical to 3.4.3's, direct and through `searchWithinBoundary`.
- **`data/raw` needing a sparse checkout in this worktree.** It is not sparse here, so the main
  checkout, which other sessions share, was never touched. My `sparsePaths` assumption was wrong.
- **`force-dynamic` being load-bearing.** My brief's claim; it is defensive only.

**Found that the plan did not predict:** Neon ignoring startup parameters (the largest), the
unbounded bbox cost, the production alias being public, the shipped SPA guard's parsing holes, and
the missing `{}` in the Kotlin's fault list.
