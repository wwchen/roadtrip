# Verification Environment (Step 5) Plan

**Goal:** Opening a PR yields a Vercel preview URL serving `apps/web` from its own Neon branch.

**Status, 2026-11-04: met by the Neon Postgres integration on the Vercel project, not by code in this repo.** The seed pipeline this plan first described was built, run end to end, and then dropped. What remains is below under "Open".

**Spec:** `docs/superpowers/specs/2026-10-27-kotlin-to-typescript-backend-design.md` §8.1, §10 step 5. Neon facts from `docs/superpowers/2026-10-28-nextjs-read-app-rulings.md` Task 5.

## How previews work now

The Neon integration (installed 2026-11-04 on project `roadtrip-web`) creates a Neon branch `preview/<git branch>` from the default branch `main` for each preview deployment, and sets that branch's `DATABASE_URL` and `DATABASE_URL_UNPOOLED` as branch-scoped Preview environment variables before the build. It also created `vercel-dev` for the Development environment and marked `main` as Vercel's production branch, which blocks deleting it. Vercel's own git-triggered previews build every branch; there is no `apps/web/vercel.json`.

The trade this accepts, decided 2026-11-04: previews read a copy of `main`'s full catalog through the integration's role, rather than a small, fixed, read-only seed. It is simpler and needs no workflow, secrets or release artifacts in this repo.

## What was tried and removed (wwchen/roadtrip#837, closed)

Measured before it was dropped, so a later attempt starts from numbers rather than guesses:

- **Seed builder.** `make seed` drove the step-4 differential with `--emit-seed` over `BC Provincial Parks` and `BC Aspira Resources`: 2 m 38 s, 0 differences, 143 campgrounds, 143 pois, 10,636 campsites. Custom dump 1.6 MB, 44 MB restored (46 MB on Neon, PostGIS 3.3.10, loaded cleanly from a 3.4 dump).
- **Restore.** Emptying only `public` breaks a second restore (`schema "drizzle" already exists`); the restore has to drop every schema the restoring role owns. The `roadtrip_web` grants do not survive the drop and must be reapplied.
- **Branch per PR.** A workflow that branched `pr-<n>` from `seed` and ran `vercel deploy --env DATABASE_URL=…` worked on #837: the preview read the seed as `roadtrip_web`. Two Vercel constraints found on the way: a CLI deploy from the repository root uploads the whole checkout (22,464 files, over the 15,000 limit), and a `git.deploymentEnabled` rule cancels the remote build of a CLI deploy whose git metadata names a disabled branch. `vercel build` on the runner plus `deploy --prebuilt` avoids both.
- **Why it went.** With the integration installed, each PR got two branches (ours and the integration's), which on the free plan's 10 leaves room for about three open PRs, and nothing else consumed the seed.

`--emit-seed` in `scripts/importer-differential.mjs` (on master since #832) is unaffected, so a seed can be built again if a later step needs one.

## Open

- **O1. Which role previews connect as.** The app was built to connect as `roadtrip_web` (SELECT-only, `default_transaction_read_only = on`). The integration's `DATABASE_URL` uses the role it was configured with. Check it in the integration's settings; if it is the owner role, a preview can write to its branch, which is harmless to `main` but means a preview no longer proves the read-only path production uses.
- **O2. Branch budget and cleanup.** Free plan: 10 branches. `main` and `vercel-dev` leave 8 for previews, one per previewed git branch. Confirm the integration's "delete obsolete branches" option is on, so a merged or deleted git branch frees its Neon branch.
- **O3. A preview gate check** (the old Task 4), if wanted: after the preview deploys, fetch a known campground's page and one bbox API call through the protection bypass header and require 200 and the expected name. Without a fixed seed the probe has to be a row that is stable in `main`.
- **O4. Pinned captures** (the old Task 5): `RawCaptureStore` pointed at a pinned fixture subset for steps 6 and 9. It depended on `seed/targets.json`, which no longer exists, so it moves to whichever of those steps first needs it and pins from that step's own target list.
