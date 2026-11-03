# Verification Environment (Step 5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Opening a PR yields a Vercel preview URL serving `apps/web` from a Neon branch restored from a versioned, deterministic seed.

**Architecture:** A pinned target list drives the step-4 differential with `--emit-seed`, so the seed is the TypeScript importer's output *proven equal to Kotlin's*. The dump is published as a versioned GitHub release asset and restored into a long-lived Neon branch `seed`. A GitHub Actions workflow branches `pr-<n>` from `seed` when a PR opens, deploys the preview with that branch's URL, and deletes the branch when the PR closes.

**Tech Stack:** Node 22 scripts (`node --test`), GitHub Actions, Neon (branching API), Vercel CLI, `pg_dump`/`pg_restore` 16.

**Spec:** `docs/superpowers/specs/2026-10-27-kotlin-to-typescript-backend-design.md` §8.1, §10 step 5. Neon facts from `docs/superpowers/2026-10-28-nextjs-read-app-rulings.md` Task 5.

## Decisions needed before Task 1

Measured 2026-11-03: project `square-sky-33743589` is on Neon's free plan, **1 GiB logical size per branch and 10 branches**. `main` holds 11,281 campgrounds and 299,857 campsites. `campsites` is 512 MB total, and 266 MB of its 418 MB of row data is `source_payload` (`pg_column_size`, so after TOAST compression). `campgrounds` is 64 MB, of which 34 MB of 57 MB is `source_payload`. Scaled to the full step-4 catalog (17,198 campgrounds, 457,023 campsites), that is about 900 MB with payloads: under 1 GiB, but with no room for a PR to write. **Without the raw payloads it is about 450 MB.** `apps/web` reads neither `source_payload` nor `detail_payload`.

- **D1. Seed scope.** *(a, recommended)* The full catalog with `campgrounds.source_payload`, `campsites.source_payload` and `tesla_superchargers.detail_payload` set to `'{}'` (their default, which satisfies their `jsonb_typeof = 'object'` CHECKs), held to ≤ `SEED_MAX_LOGICAL_BYTES` = 600 MiB. Every row and every column the app reads is real. A later step that needs payloads (the worker, step 9) loads them from `data/raw`, which is the spec's fixture source anyway. *(b)* A pinned subset of targets, if the trimmed full catalog measures over the cap. *(c)* Neon Launch plan with payloads kept.
- **D2. Where the seed version lives.** *(a, recommended)* GitHub release asset `seed-v<N>` (custom-format dump plus `manifest.json`) on this repo. *(b)* The Neon branch itself as the artifact (`seed-v<N>`): no download, but it spends the 10-branch budget and is not reproducible off Neon.
- **D3. How the preview gets its database.** *(a, recommended)* The workflow runs `vercel deploy --env DATABASE_URL=…` after creating the branch, and Vercel's own git-triggered preview builds are turned off for non-`master` branches. No race between branch creation and build. *(b)* Vercel's Neon marketplace integration: less code, but it branches from the default branch, not `seed`, and its lifecycle is not ours to test.
- **D4. Branch budget.** With 10 branches, `main` and `seed` leave **8 concurrent PR branches**. Recommended: the workflow fails with a named error when creation would exceed it, never deletes another PR's branch.

The tasks below assume D1a, D2a, D3a, D4 as written. If the trimmed catalog measures over the cap in Task 1 Step 1, fall back to D1b: `seed/targets.json` lists a subset instead of every enabled target, and nothing else changes.

## Global Constraints

- Secrets `NEON_API_KEY`, `NEON_PROJECT_ID` (= `square-sky-33743589`), `VERCEL_TOKEN`, `VERCEL_ORG_ID`, `VERCEL_PROJECT_ID` are added by the user; no task creates or prints them.
- Every third-party action is pinned by full commit SHA with the version in a trailing comment, as in `.github/workflows/ci.yml`.
- The app connects as `roadtrip_web` (SELECT-only, `default_transaction_read_only = on`), never as the owner role, on every branch.
- No vendor is ever called (spec C1, C3). Nothing in this plan fetches.
- No inline magic constants; sizes, limits and names are named `const`s or workflow `env`.
- Backend stays infra-agnostic: Neon and Vercel knowledge lives in `scripts/` and `.github/`, never in `packages/` or `apps/web/src`.

## Review Focus

1. **A PR closed while its create job is still running** leaves an orphan branch. Expected: the delete job runs after create (`concurrency` group per PR, no cancel) and deletes by name, idempotently. Test in Task 3.
2. **The 9th concurrent PR.** Expected: a red check naming the budget, other PRs' branches untouched. Test in Task 3.
3. **A PR from a fork** has no secrets. Expected: the workflow skips with a notice, never fails confusingly. Test in Task 3.
4. **A seed whose provenance does not match the current `data/raw` or migrations** (stale seed after a migration lands). Expected: the restore refuses and names the mismatch. Test in Task 2.
5. **A restore that loses the `roadtrip_web` grants**, so the preview 500s on every page. Expected: the gate check catches it by reading through the app, not by querying as owner. Test in Task 4.

---

### Task 1: Seed builder over a pinned target list

**Files:**
- Create: `seed/targets.json`, `scripts/build-seed.mjs`, `scripts/build-seed.test.mjs`
- Modify: `Makefile` (target `seed`), `scripts/importer-differential.mjs` (export `SEED_DUMP_FLAGS` already exists; export `copiedTableCounts` already exists)

**Interfaces:**
- Consumes: `runDifferential({ targets, emitSeed })` from `scripts/importer-differential.mjs`; the seed and its `.provenance.json` sidecar it writes.
- Produces: `buildSeed({ outDir, version }) -> Promise<SeedManifest>` where `SeedManifest = { version: number, dump: string, sha256: string, bytes: number, logicalBytes: number, counts: Record<string, number>, provenance: object }`, written as `<outDir>/manifest.json` beside `<outDir>/seed-v<version>.dump`.

- [ ] **Step 1: Measure.** Run `node scripts/importer-differential.mjs --emit-seed <tmp>` over every enabled target, restore it, apply the trim (Step 4), `VACUUM FULL`, and record `pg_database_size`. If it is over 600 MiB, choose a subset for `seed/targets.json` that keeps each adapter family and each sink table (D1b). Record the measurements in the commit message.
- [ ] **Step 2: Write the failing tests** in `scripts/build-seed.test.mjs`: `seed targets name only enabled registry targets`; `seed targets cover every diffed table` (via `expectedTables`); `the trim names exactly the raw payload columns and blanks them to their default` (against `TRIMMED_PAYLOAD_COLUMNS = { campgrounds: ['source_payload'], campsites: ['source_payload'], tesla_superchargers: ['detail_payload'] }`, checked against the Drizzle schema so a renamed column fails); `a manifest over SEED_MAX_LOGICAL_BYTES is refused` (pure, on a fabricated manifest); `manifest sha256 matches the dump bytes`.
- [ ] **Step 3: Run** `node --test scripts/build-seed.test.mjs`. Expected: FAIL, module not found.
- [ ] **Step 4: Implement `buildSeed`.** It calls `runDifferential` with the pinned targets and `emitSeed`, refuses on any difference (the differential already refuses to emit), so the rows are proven before they are trimmed. It then restores into a throwaway database, sets each `TRIMMED_PAYLOAD_COLUMNS` entry to `'{}'`, runs `VACUUM FULL`, measures `logicalBytes` with `pg_database_size`, and dumps in custom format. The manifest records `trimmed: TRIMMED_PAYLOAD_COLUMNS`, so a consumer can tell a trimmed payload from an empty one. Add `make seed VERSION=<n>`.
- [ ] **Step 5: Run** the tests. Expected: PASS. Then `make seed VERSION=1` once locally. Expected: manifest written, `logicalBytes` ≤ 600 MiB.
- [ ] **Step 6: Commit** `seed/targets.json` and the scripts. The dump is not committed.

### Task 2: Publish a seed version and restore it into Neon branch `seed`

**Files:**
- Create: `.github/workflows/seed.yml`, `scripts/restore-seed.mjs`, `scripts/restore-seed.test.mjs`, `scripts/neon.mjs` (thin `fetch` client over the Neon REST API: `createBranch`, `deleteBranch`, `listBranches`, `branchUri`)

**Interfaces:**
- Consumes: Task 1's `SeedManifest`.
- Produces: `restoreSeed({ manifest, dumpPath, databaseUrl }) -> Promise<void>`; `neon.mjs` exports `createBranch(name, parentName) -> {id, uri}`, `deleteBranch(name) -> boolean`, `listBranches() -> {name,id}[]`, all taking `{ apiKey, projectId }`.

- [ ] **Step 1: Failing tests:** `a seed whose provenance trees differ from HEAD is refused, naming the tree`; `a dump whose sha256 differs from the manifest is refused`; `neon client sends the parent branch id, not its name` (against a stub `fetch`).
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** `restoreSeed` verifies sha256 and provenance against `provenanceFor` at HEAD, restores with `pg_restore --no-owner --no-privileges`, then applies the `roadtrip_web` role settings and grants from `docs/web-app.md` as an idempotent SQL file `seed/grants.sql`. `seed.yml` is `workflow_dispatch` with input `version`: builds (Task 1), uploads `seed-v<N>` as a release asset, resets branch `seed`: delete it if present, create it as a child of `main` (inheriting the roles), `DROP SCHEMA public CASCADE`, then `restoreSeed`. Open PR branches keep the old seed until the PR is closed and reopened, since `ensurePreviewBranch` reuses an existing branch; document that in the release notes of each `seed-v<N>`.
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit.**

### Task 3: Branch per PR, with lifecycle and budget

**Files:**
- Create: `.github/workflows/preview.yml`, `scripts/preview-branch.mjs`, `scripts/preview-branch.test.mjs`
- Modify: `apps/web/vercel.json` (or project settings) to disable git-triggered preview deploys for non-`master` branches; `scripts/test_ci_image_builds.py` to pin the workflow shape.

**Interfaces:**
- Consumes: `neon.mjs` from Task 2.
- Produces: `ensurePreviewBranch(pr: number) -> {name, uri}`; `removePreviewBranch(pr: number) -> boolean`; constants `PREVIEW_BRANCH_PREFIX = 'pr-'`, `PREVIEW_BRANCH_BUDGET = 8`.

- [ ] **Step 1: Failing tests** (stub Neon client): `an existing pr branch is reused, not recreated`; `creation beyond the budget is refused and deletes nothing`; `removal of a branch that is already gone succeeds`; `only pr- branches count toward the budget`. Python: `test_preview_workflow_serialises_per_pr_without_cancelling` (asserts `concurrency.group` contains the PR number and `cancel-in-progress` is false) and `test_preview_workflow_skips_forks`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** `preview.yml` on `pull_request` `[opened, reopened, synchronize, closed]`, `if: github.event.pull_request.head.repo.full_name == github.repository`. Open or sync: `ensurePreviewBranch`, then `vercel deploy --env DATABASE_URL=<roadtrip_web pooled uri>` and comment the URL on the PR (update one comment, do not stack). Closed: `removePreviewBranch`.
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit.**

### Task 4: The gate: the preview serves seeded rows through the app

**Files:**
- Modify: `.github/workflows/preview.yml` (a final step), `scripts/preview-branch.mjs` (`checkPreview(url, manifest)`), its test.

**Interfaces:**
- Consumes: the deployed URL from Task 3, `SeedManifest.counts` from Task 1.
- Produces: the `preview` check that is the step-5 gate.

- [ ] **Step 1: Failing test:** `checkPreview fails when a seeded campground's page is not 200`, against a stub server. Choose the campground from the seed deterministically: the lowest `(data_provider, data_provider_ref)` in the seed, recorded in the manifest as `probe`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** The check fetches the probe campground's page and one bbox API call over it, through the protection bypass header (`VERCEL_AUTOMATION_BYPASS_SECRET`), and requires 200 and the probe's name in the body. It reads through the app as `roadtrip_web`, which is what catches Review Focus 5.
- [ ] **Step 4: Run** the tests, then open a throwaway PR. Expected: the `preview` check is green and a URL comment appears. Close it, and the `pr-<n>` branch is gone.
- [ ] **Step 5: Commit.**

### Task 5: RawCaptureStore pointed at a pinned fixture subset

**Files:**
- Create: `seed/captures.json` (slug → capture stamp, one per data source the seed targets read), `scripts/check-pinned-captures.mjs` and its test.
- Modify: `Makefile` `test` target to run the check.

**Interfaces:**
- Consumes: `RawCaptureStore` from `@roadtrip/worker`, `seed/targets.json`.
- Produces: the pinned subset later steps (6, 9) load envelopes from, by passing `rawDir` and the pinned stamps.

- [ ] **Step 1: Failing tests:** `every data source the seed targets read has exactly one pinned stamp`; `every pinned stamp exists under data/raw`; `the newest capture for a pinned slug is the pinned one` (so `loadNewestEnvelopes` and the pin cannot silently disagree).
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** the check and generate `seed/captures.json` from Task 1's provenance.
- [ ] **Step 4: Run.** Expected: PASS. Then `make test`.
- [ ] **Step 5: Commit.**
