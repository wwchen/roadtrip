# Step 4 — decisions I made on your behalf

The rulings taken while executing
[2026-10-28-typescript-importer-differential.md](plans/2026-10-28-typescript-importer-differential.md) —
the port of the Kotlin ETL importer to TypeScript and the differential that
proves the two produce identical rows. Recorded here because the execution
workspace that held them is throwaway scratch.

State at handoff, what was ported, and everything still open are in
[2026-10-28-step-4-handoff.md](2026-10-28-step-4-handoff.md). This file is the
reasoning; that one is the punchlist.

Each is a call the plan did not settle and that was taken without stopping to
ask, with what it costs if it was wrong. A large share of them correct errors in
the plan rather than deciding something new, and most of those share one cause:
a brief written from reading the Kotlin rather than from running it.

Two things to read first, because they frame everything below.

**The differential is the gate, so the port reproduces the Kotlin importer's
bugs rather than fixing them.** Where the Kotlin stores a wrong value, the
TypeScript now stores the same wrong value. Three such defects are named below
and handed to the program owner; a port that fixed them could not be proven by
the differential at all.

**Eighteen tests that could not fail were found and fixed across this step, two
of them inside the proof machinery itself.** An oracle adapter entry with no
cases once printed green and exited 0, and the oracle recorder fed the JVM a
differently-ordered document than the port read. Both are fixed. This is why
the harness and the oracle now carry unit tests of their own, and why every
gate on this branch was demonstrated failing before it was trusted.


## Pre-flight scan

- `DataSourceEntry`'s shape is pinned in Task 1 rather than left to Task 2:
  `{ slug, name, dependsOn?, fetcher: { executor?, filename?, args?, timeoutSec?, outputDirPrefix } }`.
  Task 2 cannot resolve a capture directory without `fetcher.outputDirPrefix`.
  The spec is silent; `DataSourceEntry.kt` and `Fetcher.kt` are the authority.
  Cost if wrong: two near-identical shapes, caught by the first typecheck.
- The wide-integer carrier belongs to the envelope parser (Task 2), not to the
  repo layer (Task 3). Having a later task reach into an earlier task's file to
  finish it makes the earlier review green over unfinished code. Cost if wrong:
  work done for a literal shape the captures may not hold.
- `--source <slug>` and `--emit-seed <path>` were added to the harness CLI's
  scope. Task 14 invokes both and no task defined either — a flag used by a
  later task and defined by none is a plan defect. Cost if wrong: two flags the
  measurement may show CI does not need.
- The instruction telling implementers to run the harness from the main
  checkout was removed; the fail-loudly-on-missing-captures requirement stayed
  verbatim. `data/raw` is present in the worktree and the main checkout is on
  another branch, so sending implementers there would have split the work across
  two checkouts. Cost if wrong: a genuinely sparse worktree meets a loud failure
  instead of a documented workaround — which is the required behaviour anyway.


## The harness (Task 5)

- The stale-build check being existence-only went straight into the fix loop as
  a Critical. It stat-ed `dist/cli/import.js` and nothing more, while its own
  message claimed to refuse a stale build. Tasks 6–13 each *end* on that
  command right after editing TypeScript, so it would have certified eight
  tasks against a stale `dist`. The narrow reading was mine: the brief said
  "refuses if `dist/cli/import.js` is missing". Cost had it shipped: the single
  worst failure available to this step — a gate reporting success for code that
  never executed.
- A per-table row-count assertion was added. `CampgroundRepo.kt` inserts the
  poi before the link row and the link carries `ON CONFLICT (campground_id) DO
  NOTHING`, so a port that dedupes differently leaves a poi the three-way join
  cannot see. Cost if wrong: three lines of assertion nobody needed.
- Numeric scale is compared as `jsonb`, never as text. Postgres compares jsonb
  numbers as `numeric`, so `1.0` equals `1` and `1e2` equals `100`. Cost if
  wrong: a consumer that reads these columns as text sees a difference the
  differential never reported — which is exactly what happens at the API
  boundary; see the handoffs below.
- "Expect every row reported as `missing_in_typescript` while no adapter is
  registered" was wrong in both Task 5's step 7 and the shared Tasks 6–13
  preamble, and was corrected once in each. The CLI exits non-zero with "no
  adapters registered" instead of importing nothing, so a run that *did* report
  rows missing would mean the harness had accepted a no-op import — the vacuous
  pass the plan's own review focus forbids. The step as written asked eight
  tasks to expect the defect. Cost if wrong: none; the corrected step tests
  strictly more.
- The dedupe-collision mechanism was built before Task 11 rather than at Task
  14, because the arithmetic I specified (`parsed - skipped == upserted`) cannot
  detect a collision: the repo returns the pre-dedupe row count and `distinctBy`
  runs afterwards. Cost if wrong: harness work done one task early.
- The collision counter became an **expected-count pin per target compared for
  equality**, not a fail-on-any-collision gate. Campflare's capture holds 40
  genuinely repeated campsite ids in an *array* payload, and both importers
  dedupe first-wins inside identically sized windows
  (`MAX_CATALOG_UPSERT_BATCH_SIZE` 1000 then `BULK_CHUNK_SIZE` 500, `distinctBy`
  before chunking on both sides), so for an array payload the same winner is
  chosen by construction and the gate's stated premise — that the two importers
  iterate integer-keyed objects in different orders — does not apply. Cost if
  wrong: a benign capture change turns the gate red and needs re-adjudication,
  which is loud and cheap. The narrow miss is a payload changing from array to
  object while its collision count stays identical, which takes a committed
  capture change that review would see.
- No third Campflare re-run to watch that pin go green: the pinned 40 is
  arithmetic over a count the run had already produced twice, so a 37-minute
  re-run would only re-measure 40. The pin got unit tests firing in both
  directions instead. Cost if wrong: the path from a real run's stats line into
  the pin is proven by unit test rather than by a live run.
- Aspira's campsite differential *was* re-run when a compared column
  (`campground_key`) landed after Task 11 closed. Cost if wrong: minutes.


## The Kotlin oracle (Tasks 8–10)

- The oracle is committed rather than left as scratch. Rec.gov's capture holds
  no `attributes` and no `equipment_types` on any of its 108,655 campsites, so
  two code paths were proven only by a ~300-line harness that existed nowhere in
  the repository. Cost if wrong: a committed harness needing the Gradle runtime
  classpath that later bit-rots.
- It is committed *wider* than attribute/equipment, parameterised by ETL class
  plus a case file. The reusable half is the recorded-cases plumbing, and
  scoping it to Rec.gov would force every later vendor to re-derive the
  classpath and reflection work. Cost if wrong: a larger committed harness than
  one task needs.
- The oracle builds its adapter the way the importer does — loading the real
  `poi-registry.yaml` row by slug on both sides — instead of synthesising a
  registry descriptor. Both registries are `(EtlEntry) -> adapter` keyed by the
  same names, so geometry, args, host and tenant come valid by construction.
  Landed as Task 9's first act. Cost if wrong: Task 9 opened with a rework
  instead of an adapter.
- The seeded random generator is an opt-in exploratory mode requiring an
  explicit `--seed`, never part of a default run; the deterministic gate is the
  committed case files alone. A generator drawing fresh cases each run is a
  flaky gate, but its exploratory value was real — it found disagreements. Cost
  if wrong: an exploratory mode nobody runs.
- `java-pow10.ts`'s provenance went into a committed probe script, not a
  comment. The reviewer mutated two of the 64 entries to correctly-rounded
  values and both survived, so most entries are pinned by no test and were
  trustworthy only because of a JVM verification that existed nowhere in the
  repository — and this project writes no code comments, so prose was not
  available either. Cost if wrong: a script that bit-rots, which is cheaper than
  64 unexplained float constants.
- The oracle's `check` mode is **not** wired into `make test` or CI. See Task 14
  below, which is where that was finally decided.


## The ported behaviour

- **The Kotlin `intOrNull` defect is reproduced, not fixed.**
  `JsonPrimitive.intOrNull` is null for the literal `25.0`, so the Kotlin
  importer stores NULL for `campsites.driveway_length` on 239,995 rows and
  `max_rv_length` on 234,140 — and the port now does the same. Step 4's
  deliverable is parity with the Kotlin importer's stored rows and the
  differential is the gate, so a port that fixes bugs cannot be proven by it.
  Cost if wrong: two columns stay empty for every Campflare campsite until
  someone fixes the Kotlin.
- Whole-valued numbers written with a fraction or exponent are carried with
  their source spelling through a `rawJSON` carrier in the shared envelope
  decoder, rather than per-vendor. Cost if wrong: every later adapter inherits
  it, and a decoder testing `typeof value === 'number'` on a float field
  silently misses a carrier.
- The carrier rule is general — carry the source spelling whenever
  `String(value)` differs from it — rather than two patches. Both known
  instances were the same missing rule, and the general form also moves opaque
  payloads toward Kotlin, which re-serializes literal content. Cost if wrong:
  more carriers than strictly needed; the differential staying clean is the
  proof that no stored row changes.
- **Decimal literals are deliberately NOT preserved.** `JSON.parse` normalises
  `10.0` to `10` and `1e2` to `100` where kotlinx keeps the literal text, and
  the captures hold real instances. Preserving every literal would mean every
  payload number arriving at the adapters as a carrier instead of a number,
  which breaks arithmetic across all thirteen adapters. The jsonb-not-text
  ruling absorbs the difference. Cost if wrong: a consumer comparing these
  columns as text sees a difference the differential never reported.
- The exponent model is the reviewer's probed one — `Long` mantissa
  accumulation, then a double multiply, then integrality and range checks — not
  the narrowing alternative. Throwing on an exponent-spelled carrier would stop
  the TypeScript importer dead on an input the Kotlin handles: a parity
  regression dressed as safety. Cost if wrong: a dormant divergence on a literal
  shape no capture holds, caught by committed fuzz cases rather than by any
  differential.
- `kotlinLowercase`/`kotlinUppercase` were deleted rather than wrapped. Java and
  ICU case mapping genuinely diverge in nine places, including Greek final
  sigma, and a wrapper could not handle that without reimplementing Java's
  tables and `BreakIterator` — it would promise what it cannot keep. The
  load-bearing property is narrower: no divergence reaches `[a-zA-Z0-9]`, which
  is all `normalize` and the state filter need. Verified over all 63,488 BMP
  contexts `"Σ"+c+"Σ"`: 25,963 raw string-level divergences and **zero** after
  `normalize`'s `[^a-z0-9 ]` projection. Cost if wrong: nothing the corrected
  probe will not catch, since it now walks code points rather than UTF-16 code
  units.
- Tesla and Planet Fitness are **last-wins** on an in-batch duplicate key while
  campgrounds and campsites are **first-wins**. The Kotlin is not bulk for those
  two — per-record select then update-or-insert, no `distinctBy`, no chunking —
  so the asymmetry is ported deliberately. My brief said "the same shape as the
  campground one". Cost if wrong: a latent Kotlin quirk is now a latent port
  quirk too, which is correct for step 4 regardless.
- The non-finite-coordinate divergence stands, on established grounds. Kotlin
  really does store a NaN point: `campgrounds` has no lat/lng column so nothing
  throws at encode, the Double reaches `ST_MakePoint` through a JDBC NaN bind,
  `float8` accepts it, and GiST treats a non-finite key as unindexable rather
  than erroring. TypeScript structurally cannot — `insertPoi` writes EWKT text
  and the WKT parser rejects `POINT(NaN 0)` — so the explicit guard turns a late
  parse error into an early one. The divergence is fail-closed in TypeScript and
  fail-open in Kotlin. Cost if wrong: one bad coordinate aborts a 1,000-row
  TypeScript batch where Kotlin would import 999 rows plus one broken POI, and
  the harness reports a failed import rather than a silent diff — the safe
  direction.
- ReserveCalifornia's facility ids are stored **ascending**, not in list order.
  The brief said to preserve list order; `ReserveCaliforniaCampgroundsEtl.kt:180`
  builds the field as `facilityIds.distinct().sorted()`. All 85 integer-keyed
  `Facilities` objects in the capture are already ascending, so the differential
  cannot distinguish the two readings — the code has to be right for the reason.
  Cost if wrong: a future capture whose facilities are written out of order
  silently changes `booking_provider_ref` on affected campgrounds.
- ReserveAmerica's campgrounds adapter takes `(slug, contract)`, not the three
  arguments my addendum claimed, and `fetchedAt` was dropped from its DTO
  because the Kotlin computes and never reads it. The live system won over my
  note. Cost if wrong: none; verified against the Kotlin.
- No Rec.gov re-run after `kotlinDoubleFromText` gained hexadecimal float
  support. The decimal branch is untouched and the hex branch is reached only
  where the decimal match returned null, so the change can only turn a former
  null into a number. Cost if wrong: a Rec.gov rating that used to read null now
  reads a value, which the clean Rec.gov differential would have to have been
  hiding — and it compares that column.


## Integer-key ordering — closed, and worth the machinery

This was open from Task 8 to Task 13 and is now closed, recorded here because
the measurement is what justifies the machinery.

`JSON.parse` returns integer-like object keys in ascending numeric order;
kotlinx `JsonObject` keeps the order they were written in. Across the capture
tree, exactly two input directories contain any object the engine reorders:
`recgov-campsites` (414 objects) and `reservecalifornia-catalog` (81). Of
ReserveCalifornia's 165 multi-key `UnitTypes` objects, 79 are written out of
ascending order.

- It was **accepted** at Task 8 rather than fixed: the order is observable only
  through first-wins dedupe and batch grouping, which need a duplicate natural
  key within one run, and the capture had none. The fix at that point would have
  been a hand-written order-preserving parser under every vendor, invalidating
  the differential evidence for four already-proven adapters. Cost if wrong: if
  a future capture carries a duplicate id, the two importers can pick different
  winners for that row.
- It was **fixed** at Task 13, because ReserveCalifornia made it observable
  through a compared column. The counterfactual is measured: a naive
  `Object.values` port produces **50 differences in `campsites.kind` and
  `kind_listed`**, plus 2 oracle disagreements. That number is why
  `etl/document-order.ts` exists. The solution records written key order in a
  `WeakMap` during a second textual scan in `parseKeepingDigits`, but **only for
  objects whose order differs from `Object.keys`** — so vendors whose payloads
  are already in order are untouched. Verified against CPython's
  `object_pairs_hook` for all 19,136 committed envelopes across all eight
  vendors, byte-identical. Cost: `parseKeepingDigits` is 10.2 s over the whole
  tree, of which the scan is about 1.7 s, against 5.1 s for `JSON.parse` with a
  trivial reviver.
- The sweep was a **site-by-site audit, not a blind replace**: every
  `Object.entries`/`values`/`keys` over parsed vendor JSON either moved to the
  document-order reader, where the Kotlin iterates the corresponding
  `JsonObject`, or carries a written reason why order cannot matter. It earned
  its keep — a Rec.gov-only fix would have left three more sites wrong
  (`aspira/campsites.ts`, `aspira/campsite-dictionaries.ts`, where
  `parseAttributes` keeps the *last* definition per id so order picks the winner
  outright, and `campflare/bags.ts` amenities). Eleven further sites were left
  alone with a reason each. Cost if wrong: a site switched without need is
  behaviour-neutral by construction, since recording happens only where the
  engine actually reordered; a site missed stays latent exactly as Rec.gov was.


## Dormant divergences — real, named, and unreachable on committed data

None of these can be reached by any capture in the tree today. They are recorded
because each is a named input with two known outputs, not a class of risk.

- **The quoted-number decode gap.** `json-decode` refuses a quoted number for
  `Int`/`Long` where strict `Json.Default` accepts `"12"`. Open since Task 6.
  Cost if wrong: a capture that quotes an integer field imports on the Kotlin
  side and fails on the TypeScript side.
- **`U+1DF95` reaching `address.country`.** `vendors/recgov/campgrounds.ts:176`
  emits `upper` as `address.country` when `upper.length === 2`, and
  `"\u{1DF95}"` satisfies that on *both* sides — one code point, two UTF-16
  units — so Java yields the unchanged surrogate pair and the port yields
  `"SS"`. Two different country values that both pass the length gate. `:201`
  emits `charAt(0).toUpperCase()` into the activities list on the same grounds.
  Cost if wrong: one campground's country field differs; unreachable from any
  capture.
- **Non-finite coordinates.** The oracle *cannot* compare a NaN or Infinity
  coordinate through transform, because the JVM throws when encoding the
  candidate — so those are parse-only tests — and the Kotlin repository's own
  behaviour on one is unverified end to end. Every capture under `data/raw` was
  grepped for a non-finite `Latitude`/`Longitude`: zero files. It is dormant,
  not fixed. Cost if wrong: a future capture carrying `"NaN"` has the TypeScript
  repository reject the row while the Kotlin may store it, and no gate would
  have caught the difference first.
- **ReserveAmerica's `source_payload` number spelling.** `parkExtras` emits
  `"latitude":45` where Kotlin's `Double.toString` writes `45.0`, on one row of
  266 (`park-427.json`). Tolerated rather than fixed: matching it means porting
  Java's double formatting, which differs from JavaScript's in both the
  mandatory `.0` and the scientific-notation thresholds, to serve a difference
  the jsonb-not-text ruling absorbs by design. Every `sourcePayload` consumer
  was checked — all four domain models and every adapter treat it as a parsed
  `JsonElement`, so nothing in the system can observe the spelling. The carrier
  exists for payloads preserved *verbatim* from the vendor; `parkExtras` is an
  object the adapter constructs, so there is no vendor spelling to preserve.
  This **supersedes** Task 6's note that no capture holds a `.0` spelling — one
  does. Cost if wrong: a future consumer reading `source_payload` as text sees
  `45`.
- **`serializeOpaquePayload` drops the document-order side-channel.** It is a
  plain `JSON.stringify`, so the port's `sourcePayload` *text* writes
  `Facilities`, `UnitTypes` and `Slices` ascending where Kotlin writes document
  order. Unobservable, because both columns are `jsonb` and Postgres does not
  preserve key order, and the oracle deep-equals after parsing. Left alone
  because the stored value is identical either way — but the two serialisers now
  differ in capability one function apart, and the next reader deserves to be
  told.
- **The provider allowlist seam.** The importer can write a `data_provider` that
  nothing downstream names. `DataProvider` enumerates eight values
  (`campflare`, `recgov`, `aspira`, `bcparks-strapi`, `reserveamerica`,
  `reservecalifornia`, `tesla_supercharger`, `planet_fitness_location`) while
  `poi-registry.yaml`'s `booking_providers` — the source of display copy and CTA
  verbs — names five. Nothing constrains the two sets against each other; the
  registry validator checks per-tenant `args` against `booking_providers`, not
  `data_provider`. Cost if wrong: a correctly imported row is silently not
  displayed, with no gate on either side to notice.
- **Where the Kotlin stdlib shim should live.** `kotlin-text`/`kotlin-number`/
  `kotlin-json` are a Kotlin-stdlib shim, not domain concepts, so moving one
  into `packages/domain` to dedupe a character class would put port-fidelity
  machinery in the package meant to hold the ported model.
  `packages/domain/src/carrier.ts` therefore holds a pinned duplicate of the
  whitespace class, with a test asserting the two classes are equal. The
  structural question is still open against the whole tree. Cost if wrong: one
  extra small test, and a decision deferred.
- **`toProvenance` and `AspiraJoinDto` remain unexercised.**
  `AspiraJoinDto.fetchedAt` is dead in the Kotlin too (`Instant.now()`, read
  nowhere) and is kept for DTO shape parity. Cost if wrong: no replay could
  reconcile it if anything ever reads it.


## Handoffs to the program owner

Three consequences step 4 cannot fix, because fixing them would break the parity
the differential measures.

1. **`source_payload` is served to the API as text.** `CampgroundRepo.kt:85`
   serves `COALESCE(cg.source_payload::text, '{}')` as `properties`, and `:428`
   does the same for reads. So the decimal normalisation above *is* visible at
   the API boundary: a campground whose Kotlin row holds `10.0` serves `10` from
   the TypeScript importer's row. Step 7's gate is "responses match the frozen
   contract", and a response snapshot over `properties` would differ.
2. **`driveway_length` and `max_rv_length` are NULL on ~240k Campflare
   campsites**, in both importers, because of the reproduced `intOrNull` defect.
3. **The oracle dies with the Kotlin importer.** It replays recorded cases
   against the real compiled Kotlin adapters, so when step 7 or later deletes
   them the oracle cannot run at all. Whatever evidence it currently carries for
   paths the captures do not reach has to be re-homed as ordinary unit tests
   before then, or it is lost.


## Calls on review findings

Re-grading a reviewer's severity is a call the plan does not settle, and the
cross-task context that justifies it lives nowhere else. Recorded for the same
reason as the rest.

- **Task 1, findings 3 and 4 promoted Minor → Important.** The tests could not
  distinguish `entry = last` from `entry = first`, nor `every` from `some` in the
  skip rule, because every production row has exactly one etl — so two
  assertions passed against a broken implementation. An unpinned correct choice
  is one refactor away from a silent wrong one. Cost if wrong: one synthetic
  fixture and three assertions.
- **Task 1, finding 1 fixed in Task 1 rather than deferred to Task 14.** CI never
  ran the worker suite at all, because the `package_tests` filter omitted
  `poi-registry.yaml`. The brief's own step checked the path filter and not the
  job body, so this is a plan gap. Fixing it there stopped Task 14 inheriting a
  gate that silently did not run. Cost if wrong: two CI steps land a task early.
- **Task 2, Minor 1 promoted:** typing `request` and `response` rather than
  leaving them `unknown`. Kotlin ETLs read `envelope.request.url`, so `unknown`
  would have made all eight adapter tasks cast at every use, and the parser has
  already validated those fields. Cost if wrong: one interface widened a task
  early.
- **Task 2, one more fix round rather than deferring two follow-ups.** The
  reversed-`readdir` mock moved the listing-order blind spot instead of removing
  it — a "take the first listed entry" mutant passed newest-selection, so the
  fix traded one unfalsifiable test for another; the answer is to run both
  orders. And `serializeOpaquePayload` was a convention a later implementer
  could reach around without a type error, so its return type was branded,
  following `Ewkt`/`Ewkb`. Both are seams eight later tasks consume. Cost if
  wrong: one extra round on a task that was already passing.
  - Worth keeping, as the third unfalsifiable test found on this branch: the
    page-ordering tests passed only because APFS `readdirSync` happens to return
    names ascending, so the sort itself was unprotected. GitHub runners use ext4
    with hashed directories, where readdir order is not sorted — this would have
    surfaced as a CI-only mystery if the sort had ever regressed.
- **Task 2, my error, caught by a re-review's premise check.** I told the
  implementer that "every opaque column in the schema carries a
  `jsonb_typeof(...) = 'object'` CHECK", so it made the serializer reject arrays.
  False: `tesla_superchargers.amenities`, `tesla_superchargers.pricebooks` and
  `planet_fitness_locations.amenities` are opaque array columns with `jsonbArray`
  CHECKs. A serializer rejecting arrays cannot write them, and Tesla and Planet
  Fitness were the very next task. Fix round 3 widened the guard to
  object-or-array. Cost if wrong: the guard admits a shape some opaque column
  forbids, which that column's own CHECK then rejects loudly at write time.
- **Task 3, Important 1 into the fix loop as the round's main item**, and the
  reason matters beyond that task: the `ON CONFLICT` update set had no coverage —
  dropping any one of the thirty campground or thirty-one campsite
  conflict-update keys left the suite green — and **the differential cannot catch it**, because a fresh
  differential database exercises only the INSERT path. The harness that
  justifies thin unit tests does not cover this. (`--prove-update-keys` later
  closed the other half.) Cost if wrong: one update-path test per table.
- **Task 7 blocked Task 8 on a `--prove-update-keys` run of Campflare
  Campgrounds.** Task 6 exercised the two-pass modes on POI-only sinks, so the
  campground and campsite bindings and the campsite parent-ref resolution had
  never run twice on either side — a different code path, not merely a bigger
  one. Cost if wrong: ~10 minutes against discovering a broken update path after
  six more adapters are built on it.
- **The campsite `--prove-update-keys` debt was first moved to Rec.gov Campsites
  and then, reversing myself, restored as required on Campflare Campsites before
  Task 12.** Rec.gov is the cheaper target for the same code path, which made it
  better evidence per minute; but Campflare is the raw-JSON passthrough vendor
  and five adapters were about to be built on that path. Cost of the reversal:
  roughly an hour, against discovering a broken raw-JSON update path late.
- **Task 9, the `class` field pin is taken immediately, not deferred to Task
  10.** The reviewer measured that `AspiraCampgroundsEtl` and
  `BcParksCampgroundsEtl` fingerprint identically once `class` is dropped, so
  the field is the only thing separating a test that builds the wrong class from
  one that builds the right one. Its correction to my framing is the useful
  part: a sound justification for a surviving mutant is exactly what makes the
  pin writable *now*. I had accepted "justified by a future task" where I should
  have said "justified, therefore pin it today".
- **Task 9, a vacuity floor added to `compareSigmaContexts`** — the reviewer's
  one push-back on a decline, and right: it emulated a trivially-agreeing port
  side and got exit 0, and nothing asserted the raw or compared counts, so the
  25,963-to-0 figure quoted as evidence in both the commit message and the
  report was protected by no gate. A cited number that nothing asserts is this
  branch's signature defect.
- **Task 9, finding 3 declined:** the order of the empty-inventory throw against
  the `jsonArrayOf(maps)` read is observable only if both inputs are bad at
  once, and is unreachable on any capture.
- **Task 11, finding 3 declined on the same grounds; finding 2 folded into the
  harness round rather than a fix round.** Last-writer-wins on
  `leavesByResourceLocationId` had no unit test and first-wins passed all 123,
  though it is load-bearing — one BC `resourceLocationId` is shared by three
  leaves, one PC by four, and flipping it moves the parent leaf for four real
  campsites. A 30-mutant round had missed the shape.
- **Task 12, finding 3 recorded with no code change.** The implementer's
  explanation for 21 Alberta orphans was wrong for 20 of them: only one lacks a
  latitude, the other two named are simply absent from the newest capture. The
  conclusion survives because the campsite diff is a `FULL OUTER JOIN` on the
  natural key, so "0 differences" is set equality and does not rest on the
  explanation. Cost if wrong: none to the result; a wrong reason left standing in
  a report.
- **Task 12, a commit message saying `Double.toDoubleOrNull` where it means
  `String.toDoubleOrNull` was left as is**, deliberately: unpushed at the time,
  cosmetic, and not worth rewriting three commits over.
- **Oracle floors that sat at their exact current counts were given headroom**
  (four pre-existing ones in already-closed tasks, plus `aspira-campsites`),
  against the branch's ~90% convention. An exact floor trains people to edit the
  floor rather than look. Cost if wrong: a floor that no longer fires the moment
  it should.
- **A correction to my own verification, recorded because the conclusion was
  right and the evidence was not.** I claimed the Aspira six-target re-run
  covered the document-order framework change, on the grounds that Aspira has
  the most integer-keyed payload history. Measured by the reviewer: Aspira's
  1,641 capture files trip the prefilter zero times and record zero objects, so
  that run exercised a regex test and nothing else of the new code. Rec.gov was
  the run to make, and it was then made.


## Task 14 — the full set, the gates and the CI job

### What the three modes are for

The brief treats them as three runs of one thing. They are not, and the
measurements below only make sense against what each can see. All three were
demonstrated failing before any was trusted.

- **The single pass** is the strongest check of parse and transform, and has by
  far the most rows to lose. It is the only mode in CI.
- **`--passes 2`** reaches a conflict-handling defect that the single pass
  cannot, because a first import into an empty database exercises the INSERT
  path alone. Demonstrated: with the campground `onConflictDoUpdate` clause
  removed entirely, the single pass reports `0 differences` and exits 0 while
  `--passes 2` fails on pass 2. It was described here and in the Makefile as
  *the only* mode that can see such a defect, and that was an overclaim:
  `passPlan` forces `--prove-update-keys` to at least two passes too, so it
  reaches the conflict path as well. Whether it strictly subsumes `--passes 2`
  — which would make a 56-minute mode of `make importer-diff` redundant — is
  open. No defect has been found that `--passes 2` catches and
  `--prove-update-keys` misses, and none has been looked for. Ruling: keep both
  and correct the claim. Cost if wrong: 56 minutes per pre-land gate run,
  against dropping a gate on an unproven redundancy.
- **`--prove-update-keys`** blanks every compared column between the passes, so a
  column missing from one importer's `ON CONFLICT` update set stays blank on
  that side alone. Demonstrated: with `name` dropped from the campground
  conflict-update keys, the single pass and `--passes 2` are both clean and
  `--prove-update-keys` reports a `name` difference on every campground row.
  **The middle result is not a failure, it is the point** — with identical
  captures a dropped update key leaves the correct pass-1 value in place, so
  `--passes 2` structurally cannot see it, and an assertion expecting it to
  would send the next person debugging a correct harness.

Two corrections to the brief's own expectations for those proofs, both of which
make the probes it specified invalid:

- **`statusDescription` cannot be used as the dropped-key probe.** No adapter in
  any of the eight vendors ever populates it, so `campgrounds.status_description`
  is NULL in every capture; and the harness blanks a nullable column *to NULL*.
  Dropping it from the conflict-update keys is therefore invisible to all three
  modes by construction — measured, not argued: the full three-mode run over the
  probe subset came back clean with the key dropped. A valid probe has to be a
  column the captures actually fill, which is why `name` is used above.
- **Removing `targetWhere` fails on pass 1, not on re-import.**
  `campgrounds_provider_uidx` is a *partial* unique index
  (`WHERE deleted_at IS NULL`), so an `ON CONFLICT` with no matching predicate
  is rejected outright by Postgres with `42P10`, "there is no unique or
  exclusion constraint matching the ON CONFLICT specification", before any row
  lands. The brief expected a clean single pass and a failing `--passes 2`. That
  is the right shape for the property but the wrong mutation to reach it;
  removing the whole conflict clause is the one that produces it.

### The CI decision

The rule the brief set: CI runs the full capture set in all three modes if the
whole job lands under 30 minutes on a GitHub runner. Measured on an Apple
Silicon laptop, each number read from the run's own exit code:

| Run | Wall clock | Exit |
| --- | --- | --- |
| Full set, single pass | 1,981 s — 33 m 01 s | 0 |
| Full set, `--passes 2` | 3,352 s — 55 m 52 s | 0 |
| Full set, `--prove-update-keys` | 3,704 s — 61 m 44 s | 0 |
| **All three** | **9,037 s — 2 h 30 m 37 s** | |
| 13-family subset, `--passes 2` | >1,481 s, did not finish | 2 |
| 13-family subset, `--prove-update-keys` | not measured | 2 |

All three full-set runs are clean: 17,198 campgrounds, 457,023 campsites, 24,229
pois, 1,566 Planet Fitness locations, 5,465 Tesla superchargers and 18 import
runs identical on both sides, every collision count exactly on its pin
(`Campflare Campsites` 40, the other seventeen 0), `0 differences across every
diffed table`.

**Decision: the fallback branch, and not close.** 2 h 30 m against a 30-minute
bar, on a machine faster than a GitHub runner. CI runs the **single pass over
the full capture set**; `--passes 2` and `--prove-update-keys` over the full set
become the `make importer-diff` gate before landing.

**The subset branch was rejected on structure, with a partial measurement
behind it rather than a complete one.** The two subset runs were lost to the
Docker VM filling its disk — the `--passes 2` run had completed its entire
Kotlin side and 8 of 13 TypeScript targets at 1,481 s (24 m 41 s) when it died,
so it was already past 24 minutes before reaching the five campsite targets,
and the `--prove-update-keys` run never got a container. Those are the two
numbers in this document that are a lower bound and an absence rather than a
measurement, and they are named as such because the structural argument below
is what actually decides it.

Two things about the subset branch are worth stating, because they are what
makes it pointless rather than merely slow. There are **thirteen adapter
families** across the eighteen targets — `AspiraCampgroundsEtl`,
`AspiraCampsitesEtl` and `BcParksCampgroundsEtl` are three families inside what
looks like one vendor — and a campsite family cannot be selected without its
parent campground target, because campsite adapters resolve parents through
`campgrounds.(data_provider, data_provider_ref)` and a subset without the parent
would import zero campsites and trip the both-sides-wrote guard. So the subset is
forced to contain `Campflare Campsites` (299,857 rows) and `Rec.gov Campsites`
(105,452), which is ~89% of the catalog's campsites. It drops five of eighteen
targets and almost none of the cost.

### Other Task 14 rulings

- **`--emit-seed` silently wrote nothing on the full capture set, and still
  exited 0.** Its condition was "no differences and *no collisions at all*",
  and `Campflare Campsites` is pinned at 40 adjudicated collisions, so on any
  full-set run the dump was skipped and the run reported success. The condition
  is now the run's own clean verdict — the same one the exit code is taken from:
  no differences, and every collision equal to its pin. A pinned collision is
  adjudicated; a count off its pin is not, and that is what refuses. A refusal
  now also says so on stderr rather than leaving an absent file to be noticed.
  Cost if wrong: a seed is emitted from a run carrying 40 dedupe collisions whose
  winner both importers choose identically — which is the condition the pin
  exists to assert. **The fix is proven by unit test, not end to end**: the
  full-set `--emit-seed` run that would have produced the seed dump itself never
  got to run, so the dump named by spec §8.1 step 5 does not yet exist. What is
  committed is the target and the provenance format, which is what the brief
  asked for; the artifact is still owed.
- **The registry's unregistered-adapter tolerance is closed.** While the
  registry was being filled in, `importTargets` skipping a row whose adapter was
  not yet registered was correct. Now that all thirteen adapters exist it is a
  hazard: a typo in an `adapter:` value silently shrinks the target set and the
  import reports success over fewer sources, where Kotlin fails loudly in
  `createPoiTerminal`. Every enabled `poi_data` and `campsite_data` row must now
  resolve an adapter, and the failure names the section, the adapter, the row
  and the slug. Demonstrated against a *copy* of the registry with one
  `adapter:` value misspelled: the CLI exits 1 with `Unknown campsite_data
  adapter: ReserveCaliforniaSiteEtl (row='California State Park Sites',
  slug=reservecalifornia-campsites)`, where the same registry without the
  requirement exits 0 and lists seventeen targets instead of eighteen. Cost if
  wrong: the check is carried on a `CliIo` field rather than being unconditional,
  so the production entry point asserts it and the tests that drive the CLI with
  a deliberately partial registry are the only callers that opt out. A future
  production entry point that forgets the field inherits the old tolerance.
- **The oracle's `check` mode stays out of `make test` and CI**, finally
  deciding what Task 10 deferred here. It needs the backend Kotlin classes
  compiled *and* the worker suite run under a recorder — two toolchains in one
  job — and its value is as a porting instrument, not a standing regression
  gate: it cannot run at all once the Kotlin importer is deleted. What *is*
  wired into `make test` is `scripts/etl-oracle.test.mjs` (37 tests, under a
  second, no JVM), which tests the comparison machinery itself — where two of
  this step's eighteen unfalsifiable tests lived. Cost if wrong: a Kotlin-parity
  regression in an adapter path the captures do not reach ships unnoticed between
  manual `check` runs.
- **`scripts/importer-differential.test.mjs` is added to `make test`**, and so
  is the oracle machinery test above. Neither ran anywhere before — not in
  `make test`, not in CI — so the harness that certifies thirteen adapters was
  itself ungated. Cost if wrong: `make test` boots one more PostGIS container.
- **The full differential is not in `make test`.** It reads `data/raw`
  (138 MiB packed, absent from a sparse worktree) and takes hours where
  `make test` takes minutes. `make importer-diff` is the pre-land gate and runs
  all three modes, one per recipe line so make stops on the first non-zero and
  no mode's result is read through another's. Cost if wrong: a conflict-handling
  or update-key regression reaches `master` if someone lands a change to either
  importer without running it.

### Carried, and still open

- **The ReserveCalifornia gap is closed.** Task 13 left its differential
  un-re-run across `e2a920b0`, `ef8d3171` and `24d92bab`, the commits that moved
  `compareLongs` between modules and changed object-iteration sites in four
  other vendors. Both California targets ran inside the clean full-set single
  pass above — 108 campgrounds and 8,357 campsites identical on both sides, 0
  collisions against pins of 0 — so the gap is verified, not assumed.
- **The Docker VM filled its disk partway through this task's measurements**,
  which is what cost the two subset runs and, at the time, `make test` and the
  seed dump. Both have since run: `make test` exits 0 in 7 m 01 s with the
  harness file inside it (47 tests, 0 failures). The cause is 418 anonymous, unreferenced Testcontainers volumes holding
  96 GB, every one created in the 36 hours this step's differentials ran; the
  VM's overlay is 126 G with 0 available and `initdb` now fails with "No space
  left on device" before any gate can start. `make reclaim` cannot clear them by
  design — it prunes only labelled Roadtrip resources and deliberately leaves
  anonymous volumes alone so an unrelated stack sharing the machine stays out of
  range, which is the right policy: a blanket `docker volume prune` would also
  destroy `cc-rewards-tracker-prod_db-data` and the Roadtrip Grafana, Loki,
  Prometheus and Tempo volumes, all of which are dangling. Removing the 418 by
  id is the safe operation and needs a human.

  **Root cause found and fixed.** `withPostgis` starts its container with
  `docker run -d --rm` and tears it down with `docker rm -f`, which does not
  take the container's anonymous volumes with it; `postgis/postgis:16-3.4`
  declares `VOLUME /var/lib/postgresql/data`, so every harness container
  orphaned one data volume. Three teardown sites now pass `-v`
  (`schema-reference.mjs:323`, `schema-equivalence.test.mjs:98` and `:151`).
  This is not a side issue: the full disk wedged the Docker VM mid-run, which
  is what turned one stalled test into a three-hour `make test` and sent this
  task chasing an emulation theory and then a phantom deadlock before the
  daemon turned out to be the cause.

- **`scripts/` carries explanatory inline comments throughout this branch,
  against the project's standing "no code comments" rule.** The harness's
  comments are the record of what is compared and why, and of which guards exist
  because a gate was once vacuous — several would be hard to reconstruct from the
  code. Whether the harness is an exception is the user's call, not one to settle
  by stripping them. The new `make importer-diff` target and the CI job carry
  comments on the same reading, since the brief requires the measurements to be
  recorded in the job's comment. Nothing else added on this branch carries one.
- **The Rec.gov and Campflare *campground* collision pins no longer rest on
  pre-pin runs.** They were carried as such until Task 14, when all three
  full-set modes ran with the gate live and reported every one of the eighteen
  targets as `(pinned N)` — so each pin on the branch is now measured against
  the pin in place, three times over. Recorded because the weaker claim stood
  in this file first.
- **`staleBuilds` can never report a current tree.** `apps/web` has a build
  script and a `src/` but emits to `.next`, not `dist`, so it is permanently
  reported stale and every differential run rebuilds the worker before it
  starts. It fails safe — the direction that costs seconds, not correctness —
  but it means the harness's "a current build neither rebuilds nor refuses"
  property holds only in its own fixtures. Cost if wrong: ~15 s per run, and a
  guard whose quiet path is untested in production use.
- **The CLI reports a failed query without its Postgres cause.** `runCli` prints
  `error.message` only, which for a Drizzle failure is "Failed query:" plus the
  SQL and every bound parameter — tens of kilobytes — and omits the `code`,
  `constraint_name` and `detail` that say what actually went wrong. The `42P10`
  above had to be reproduced separately to be named. Cost if wrong: every
  importer failure costs a reproduction step to diagnose.
